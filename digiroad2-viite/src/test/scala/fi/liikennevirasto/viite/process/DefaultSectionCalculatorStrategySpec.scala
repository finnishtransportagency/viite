package fi.liikennevirasto.viite.process

import fi.liikennevirasto.GeometryUtils
import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset.SideCode.TowardsDigitizing
import fi.liikennevirasto.digiroad2.asset.{LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.RoadType
import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.process.strategy.DefaultSectionCalculatorStrategy
import fi.liikennevirasto.viite.util.CalibrationPointsUtils
import org.joda.time.DateTime
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
  val roadwayDAO = new RoadwayDAO
  val linearLocationDAO = new LinearLocationDAO
  val projectDAO = new ProjectDAO
  val projectReservedPartDAO = new ProjectReservedPartDAO
  val projectLinkDAO = new ProjectLinkDAO

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

  def buildTestDataForProject(project: Option[Project], rws: Option[Seq[Roadway]], lil: Option[Seq[LinearLocation]], pls: Option[Seq[ProjectLink]]): Unit = {
    if (rws.nonEmpty)
      roadwayDAO.create(rws.get)
    if (lil.nonEmpty)
      linearLocationDAO.create(lil.get, "user")
    if (project.nonEmpty)
      projectDAO.create(project.get)
    if (pls.nonEmpty) {
      if (project.nonEmpty) {
        val roadParts = pls.get.groupBy(pl => (pl.roadNumber, pl.roadPartNumber)).keys
        roadParts.foreach(rp => projectReservedPartDAO.reserveRoadPart(project.get.id, rp._1, rp._2, "user"))
        projectLinkDAO.create(pls.get.map(_.copy(projectId = project.get.id)))
      } else {
        projectLinkDAO.create(pls.get)
      }
    }
  }

  def toRoadwayAndLinearLocation(p: ProjectLink):(LinearLocation, Roadway) = {
    val startDate = p.startDate.getOrElse(DateTime.now()).minusDays(1)

    (LinearLocation(p.linearLocationId, 1, p.linkId, p.startMValue, p.endMValue, p.sideCode, p.linkGeometryTimeStamp,
      (CalibrationPointsUtils.toCalibrationPointReference(p.startCalibrationPoint),
        CalibrationPointsUtils.toCalibrationPointReference(p.endCalibrationPoint)),
      p.geometry, p.linkGeomSource,
      p.roadwayNumber, Some(startDate), p.endDate),
      Roadway(p.roadwayId, p.roadwayNumber, p.roadNumber, p.roadPartNumber, p.roadType, p.track, p.discontinuity, p.startAddrMValue, p.endAddrMValue, p.reversed, startDate, p.endDate,
        p.createdBy.getOrElse("-"), p.roadName, p.ely, TerminationCode.NoTermination, DateTime.now(), None))
  }

  test("Test defaultSectionCalculatorStrategy.assignMValues() and findStartingPoints When using 4 geometries that end up in a point " +
    "Then return the same project links, but now with correct MValues and directions") {
    runWithRollback {
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
  }

  test("Test defaultSectionCalculatorStrategy.assignMValues() When using 2 tracks with huge discontinuity and not proper road type sections Then they will fail if the sections cannot be adjusted for two tracks due to their huge discontinuity") {
    runWithRollback {
      val geomLeft1 = Seq(Point(0.0, 0.0), Point(0.0, 30.0))
      val geomLeft2 = Seq(Point(0.0, 30.0), Point(0.0, 60.0))

      val projId = Sequences.nextViiteProjectId
      val roadwayId = Sequences.nextRoadwayId
      val linearLocationId = Sequences.nextLinearLocationId
      val projectLinkId = Sequences.nextProjectLinkId
      val project = Project(projId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val projectLinkLeft1 = ProjectLink(projectLinkId, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        Some("user"), 12345L, 0.0, 0.0, SideCode.Unknown, (None, None),
        geomLeft1, projId, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft1), 0L, 0, 0, reversed = false,
        None, 86400L, roadwayNumber = 12345L)
      val projectLinkLeft2 = ProjectLink(projectLinkId + 1, 9999L, 1L, Track.apply(2), Discontinuity.Discontinuous, 0L, 0L, 0L, 0L, None, None,
        Some("user"), 12346L, 0.0, 0.0, SideCode.Unknown, (None, None),
        geomLeft2, projId, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft2), 0L, 0, 0, reversed = false,
        None, 86400L, roadwayNumber = 12345L)

      val geomRight1 = Seq(Point(5.0, 0.0), Point(5.0, 5.0))
      val geomRight2 = Seq(Point(5.0, 5.0), Point(5.0, 62.0))

      val projectLinkRight1 = ProjectLink(projectLinkId + 2, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 5L, 0L, 5L, None, None,
        Some("user"), 12347L, 0.0, 5.0, SideCode.Unknown, (Some(ProjectLinkCalibrationPoint(123457L, 0.0, 0, CalibrationPointSource.ProjectLinkSource)), None),
        geomRight1, projId, LinkStatus.Transfer, RoadType.FerryRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight1), roadwayId, linearLocationId, 0, reversed = false,
        None, 86400L, roadwayNumber = 12346L)

      val projectLinkRight2 = ProjectLink(projectLinkId + 3, 9999L, 1L, Track.apply(1), Discontinuity.Discontinuous, 5L, 62L, 5L, 62L, None, None,
        Some("user"), 12348L, 0.0, 57.0, SideCode.Unknown, (None, Some(ProjectLinkCalibrationPoint(123458L, 57.0, 62, CalibrationPointSource.ProjectLinkSource))),
        geomRight2, projId, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight2), roadwayId + 1, linearLocationId + 1, 0, reversed = false,
        None, 86400L, roadwayNumber = 12347L)

      val leftSideProjectLinks = Seq(projectLinkLeft1, projectLinkLeft2)
      val rightSideProjectLinks = Seq(projectLinkRight1, projectLinkRight2)
      val (linearLocation1, roadway1) = Seq(projectLinkRight1).map(toRoadwayAndLinearLocation).head
      val (linearLocation2, roadway2) = Seq(projectLinkRight2).map(toRoadwayAndLinearLocation).head

      buildTestDataForProject(Some(project), Some(Seq(roadway1, roadway2)), Some(Seq(linearLocation1, linearLocation2)), Some(leftSideProjectLinks ++ rightSideProjectLinks))

      intercept[NoSuchElementException] {
        defaultSectionCalculatorStrategy.assignMValues(leftSideProjectLinks, rightSideProjectLinks, Seq.empty[UserDefinedCalibrationPoint])
      }
    }
  }

  test("Test defaultSectionCalculatorStrategy.assignMValues() When using 2 tracks with proper pairing road type sections Then they will calculate values properly") {
    runWithRollback {
      val geomLeft1 = Seq(Point(0.0, 0.0), Point(0.0, 30.0))
      val geomLeft2 = Seq(Point(0.0, 30.0), Point(0.0, 60.0))

      val projId = Sequences.nextViiteProjectId
      val roadwayId = Sequences.nextRoadwayId
      val linearLocationId = Sequences.nextLinearLocationId
      val projectLinkId = Sequences.nextProjectLinkId
      val project = Project(projId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val projectLinkLeft1 = ProjectLink(projectLinkId, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        Some("user"), 12345L, 0.0, 0.0, SideCode.Unknown, (None, None),
        geomLeft1, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft1), 0L, 0, 0, reversed = false,
        None, 86400L, roadwayNumber = 12345L)
      val projectLinkLeft2 = ProjectLink(projectLinkId+1, 9999L, 1L, Track.apply(2), Discontinuity.Discontinuous, 0L, 0L, 0L, 0L, None, None,
        Some("user"), 12346L, 0.0, 0.0, SideCode.Unknown, (None, None),
        geomLeft2, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft2), 0L, 0, 0, reversed = false,
        None, 86400L, roadwayNumber = 12345L)

      val geomRight1 = Seq(Point(5.0, 0.0), Point(5.0, 5.0))
      val geomRight2 = Seq(Point(5.0, 5.0), Point(5.0, 62.0))

      val projectLinkRight1 = ProjectLink(projectLinkId+2, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 5L, 0L, 5L, None, None,
        Some("user"), 12347L, 0.0, 5.0, SideCode.Unknown, (None, None),
        geomRight1, 0L, LinkStatus.Transfer, RoadType.FerryRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight1), roadwayId, linearLocationId, 0, reversed = false,
        None, 86400L, roadwayNumber = 12346L)

      val projectLinkRight2 = ProjectLink(projectLinkId+3, 9999L, 1L, Track.apply(1), Discontinuity.Discontinuous, 5L, 62L, 5L, 62L, None, None,
        Some("user"), 12348L, 0.0, 57.0, SideCode.Unknown, (None, None),
        geomRight2, 0L, LinkStatus.Transfer, RoadType.FerryRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight2), roadwayId, linearLocationId+1, 0, reversed = false,
        None, 86400L, roadwayNumber = 12347L)

      val leftSideProjectLinks = Seq(projectLinkLeft1, projectLinkLeft2)
      val rightSideProjectLinks = Seq(projectLinkRight1, projectLinkRight2)

      val (linearLocation1, roadway1) = Seq(projectLinkRight1).map(toRoadwayAndLinearLocation).head
      val (linearLocation2, roadway2) = Seq(projectLinkRight2).map(toRoadwayAndLinearLocation).head

      buildTestDataForProject(Some(project), Some(Seq(roadway1.copy(endAddrMValue = roadway2.endAddrMValue))), Some(Seq(linearLocation1, linearLocation2)), Some(leftSideProjectLinks ++ rightSideProjectLinks))

      val projectLinksWithAssignedValues = defaultSectionCalculatorStrategy.assignMValues(leftSideProjectLinks, rightSideProjectLinks, Seq.empty[UserDefinedCalibrationPoint])
      projectLinksWithAssignedValues.filter(pl => pl.endAddrMValue == 0L).isEmpty should be (true)
    }
  }

  test("Test defaultSectionCalculatorStrategy.assignMValues() When using 2 tracks (mismatching link numbers) with proper pairing road type sections Then they will calculate values properly") {
    runWithRollback {
      val geomLeft1 = Seq(Point(0.0, 0.0), Point(0.0, 60.0))

      val projId = Sequences.nextViiteProjectId
      val roadwayId = Sequences.nextRoadwayId
      val linearLocationId = Sequences.nextLinearLocationId
      val projectLinkId = Sequences.nextProjectLinkId
      val project = Project(projId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val projectLinkLeft1 = ProjectLink(projectLinkId, 9999L, 1L, Track.apply(2), Discontinuity.Discontinuous, 0L, 0L, 0L, 0L, None, None,
        Some("user"), 12345L, 0.0, 0.0, SideCode.Unknown, (None, None),
        geomLeft1, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft1), 0L, 0, 0, reversed = false,
        None, 86400L, roadwayNumber = 12345L)

      val geomRight1 = Seq(Point(5.0, 0.0), Point(5.0, 5.0))
      val geomRight2 = Seq(Point(5.0, 5.0), Point(5.0, 62.0))

      val projectLinkRight1 = ProjectLink(projectLinkId+2, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 5L, 0L, 5L, None, None,
        Some("user"), 12347L, 0.0, 5.0, SideCode.Unknown, (None, None),
        geomRight1, 0L, LinkStatus.Transfer, RoadType.FerryRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight1), roadwayId, linearLocationId, 0, reversed = false,
        None, 86400L, roadwayNumber = 12346L)

      val projectLinkRight2 = ProjectLink(projectLinkId+3, 9999L, 1L, Track.apply(1), Discontinuity.Discontinuous, 5L, 62L, 5L, 62L, None, None,
        Some("user"), 12348L, 0.0, 57.0, SideCode.Unknown, (None, None),
        geomRight2, 0L, LinkStatus.Transfer, RoadType.FerryRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight2), roadwayId, linearLocationId+1, 0, reversed = false,
        None, 86400L, roadwayNumber = 12347L)

      val leftSideProjectLinks = Seq(projectLinkLeft1)
      val rightSideProjectLinks = Seq(projectLinkRight1, projectLinkRight2)

      val (linearLocation1, roadway1) = Seq(projectLinkRight1).map(toRoadwayAndLinearLocation).head
      val (linearLocation2, roadway2) = Seq(projectLinkRight2).map(toRoadwayAndLinearLocation).head

      buildTestDataForProject(Some(project), Some(Seq(roadway1.copy(endAddrMValue = roadway2.endAddrMValue))), Some(Seq(linearLocation1, linearLocation2)), Some(leftSideProjectLinks ++ rightSideProjectLinks))

      val projectLinksWithAssignedValues = defaultSectionCalculatorStrategy.assignMValues(leftSideProjectLinks, rightSideProjectLinks, Seq.empty[UserDefinedCalibrationPoint])
      projectLinksWithAssignedValues.filter(pl => pl.endAddrMValue == 0L).isEmpty should be (true)
    }
  }
  /*
       ^
        \    <- #2 Transfer
         \   <- #1 Transfer
          \  <- #3 New
   */
  test("Test findStartingPoints When adding one (New) link before the existing (Transfer) road Then the road should still maintain the previous existing direction") {
    runWithRollback {
      val geomTransfer1 = Seq(Point(30.0, 20.0), Point(20.0, 30.0))
      val geomTransfer2 = Seq(Point(20.0, 30.0), Point(10.0, 40.0))
      val plId = Sequences.nextProjectLinkId

      val projectLink1 = ProjectLink(plId + 1, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 15L, 15L, 30L, None, None,
        None, 12345L, 0.0, 15.0, SideCode.TowardsDigitizing, (None, None),
        geomTransfer1, 0L, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransfer1), 0L, 0, 0, reversed = false,
        None, 86400L)
      val projectLink2 = ProjectLink(plId + 2, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 15L, 30L, 30L, 45L, None, None,
        None, 12346L, 0.0, 15.0, SideCode.TowardsDigitizing, (None, None),
        geomTransfer2, 0L, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransfer2), 0L, 0, 0, reversed = false,
        None, 86400L)

      val geomNew3 = Seq(Point(40.0, 10.0), Point(30.0, 20.0))

      val projectLinkNew3 = ProjectLink(plId + 3, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12347L, 0.0, 0.0, SideCode.Unknown, (None, None),
        geomNew3, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew3), 0L, 0, 0, reversed = false,
        None, 86400L)

      val otherProjectLinks = Seq(projectLink1, projectLink2)
      val newProjectLinks = Seq(projectLinkNew3)

      val startingPointsForCalculations = defaultSectionCalculatorStrategy.findStartingPoints(newProjectLinks, otherProjectLinks, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
      startingPointsForCalculations should be((geomNew3.head, geomNew3.head))
    }
  }

  /*
       ^
        \    <- #2 Transfer
         \   <- #1 Transfer
          \  <- #3 New (inverted geometry)
   */
  test("Test findStartingPoints When adding one (New) link with inverted geometry before the existing (Transfer) road Then the road should still maintain the previous existing direction") {
    runWithRollback {
      val geomTransfer1 = Seq(Point(30.0, 20.0), Point(20.0, 30.0))
      val geomTransfer2 = Seq(Point(20.0, 30.0), Point(10.0, 40.0))
      val plId = Sequences.nextProjectLinkId

      val projectLink1 = ProjectLink(plId + 1, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 15L, 15L, 30L, None, None,
        None, 12345L, 0.0, 15.0, SideCode.TowardsDigitizing, (None, None),
        geomTransfer1, 0L, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransfer1), 0L, 0, 0, reversed = false,
        None, 86400L)
      val projectLink2 = ProjectLink(plId + 2, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 15L, 30L, 30L, 45L, None, None,
        None, 12346L, 0.0, 15.0, SideCode.TowardsDigitizing, (None, None),
        geomTransfer2, 0L, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransfer2), 0L, 0, 0, reversed = false,
        None, 86400L)

      val geomNew3 = Seq(Point(30.0, 20.0), Point(40.0, 10.0))

      val projectLinkNew3 = ProjectLink(plId + 3, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12347L, 0.0, 0.0, SideCode.Unknown, (None, None),
        geomNew3, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew3), 0L, 0, 0, reversed = false,
        None, 86400L)

      val otherProjectLinks = Seq(projectLink1, projectLink2)
      val newProjectLinks = Seq(projectLinkNew3)

      val startingPointsForCalculations = defaultSectionCalculatorStrategy.findStartingPoints(newProjectLinks, otherProjectLinks, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
      startingPointsForCalculations should be((geomNew3.last, geomNew3.last))
    }
  }

  /*
         \  <- #1
             (minor discontinuity)
           \  <- #2
   */
  private def testFindStartingPointsWithOneMinorDiscontinuity(sideCode: SideCode, linkStatus: LinkStatus): Unit = {
    runWithRollback {
      val geom1 = Seq(Point(10.0, 20.0), Point(0.0, 30.0))
      val plId = Sequences.nextProjectLinkId

      val projectLink1 = ProjectLink(plId + 1, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 15L, 0L, 15L, None, None,
        None, 12345L, 0.0, 15.0, sideCode, (None, None),
        geom1, 0L, linkStatus, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom1), 0L, 0, 0, reversed = false,
        None, 86400L)

      val geomNew2 = Seq(Point(30.0, 0.0), Point(20.0, 10.0))

      // Notice that discontinuity value should not affect calculations, which are based on geometry. That's why we have here this value "Continuous".
      val projectLinkNew2 = ProjectLink(plId + 3, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12347L, 0.0, 0.0, SideCode.Unknown, (None, None),
        geomNew2, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew2), 0L, 0, 0, reversed = false,
        None, 86400L)

      val otherProjectLinks = Seq(projectLink1)
      val newProjectLinks = Seq(projectLinkNew2)

      val startingPointsForCalculations = defaultSectionCalculatorStrategy.findStartingPoints(newProjectLinks, otherProjectLinks, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
      if (sideCode == SideCode.TowardsDigitizing) {
        startingPointsForCalculations should be((geomNew2.head, geomNew2.head))
      } else {
        startingPointsForCalculations should be((geom1.last, geom1.last))
      }
    }
  }

  /*
        ^
         \  <- #1 New
             (minor discontinuity)
           \  <- #2 New
   */
  test("Test findStartingPoints When adding one (New) link with minor discontinuity before the existing (New) road Then the road should still maintain the previous existing direction") {
    testFindStartingPointsWithOneMinorDiscontinuity(SideCode.TowardsDigitizing, LinkStatus.New)
  }

  /*
         \  <- #1 New
             (minor discontinuity)
           \  <- #2 New
            v
   */
  test("Test findStartingPoints When adding one (New) link with minor discontinuity after the existing (New) road (against digitization) Then the road should still maintain the previous existing direction") {
    testFindStartingPointsWithOneMinorDiscontinuity(SideCode.AgainstDigitizing, LinkStatus.New)
  }

  /*
        ^
         \  <- #1 Transfer
             (minor discontinuity)
           \  <- #2 New
   */
  test("Test findStartingPoints When adding one (New) link with minor discontinuity before the existing (Transfer) road Then the road should still maintain the previous existing direction") {
    testFindStartingPointsWithOneMinorDiscontinuity(SideCode.TowardsDigitizing, LinkStatus.Transfer)
  }

  /*
         \  <- #1 Transfer
             (minor discontinuity)
           \  <- #2 New
            v
   */
  test("Test findStartingPoints When adding one (New) link with minor discontinuity after the existing (Transfer) road (against digitization) Then the road should still maintain the previous existing direction") {
    testFindStartingPointsWithOneMinorDiscontinuity(SideCode.AgainstDigitizing, LinkStatus.Transfer)
  }

  /*
        ^
         \  <- #1 NotHandled
             (minor discontinuity)
           \  <- #2 New
   */
  test("Test findStartingPoints When adding one (New) link with minor discontinuity before the existing (NotHandled) road Then the road should still maintain the previous existing direction") {
    testFindStartingPointsWithOneMinorDiscontinuity(SideCode.TowardsDigitizing, LinkStatus.NotHandled)
  }

  /*
         \  <- #1 NotHandled
             (minor discontinuity)
           \  <- #2 New
            v
   */
  test("Test findStartingPoints When adding one (New) link with minor discontinuity after the existing (NotHandled) road (against digitization) Then the road should still maintain the previous existing direction") {
    testFindStartingPointsWithOneMinorDiscontinuity(SideCode.AgainstDigitizing, LinkStatus.NotHandled)
  }

  /*
         \  <- #1
             (minor discontinuity)
           \  <- #2
               (minor discontinuity)
             \  <- #3
   */
  private def testFindStartingPointsWithTwoMinorDiscontinuitiesNewInMiddle(sideCode: SideCode, linkStatus: LinkStatus): Unit = {
    runWithRollback {
      val geom1 = Seq(Point(10.0, 40.0), Point(0.0, 50.0))
      val plId = Sequences.nextProjectLinkId

      val projectLink1 = ProjectLink(plId + 1, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 15L, 0L, 15L, None, None,
        None, 12345L, 0.0, 15.0, sideCode, (None, None),
        geom1, 0L, linkStatus, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom1), 0L, 0, 0, reversed = false,
        None, 86400L)

      val geomNew2 = Seq(Point(30.0, 20.0), Point(20.0, 30.0))
      val geomNew3 = Seq(Point(50.0, 0.0), Point(40.0, 10.0))

      // Notice that discontinuity value should not affect calculations, which are based on geometry. That's why we have here this value "Continuous".
      val projectLinkNew2 = ProjectLink(plId + 2, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12347L, 0.0, 0.0, SideCode.Unknown, (None, None),
        geomNew2, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew2), 0L, 0, 0, reversed = false,
        None, 86400L)
      val projectLinkNew3 = ProjectLink(plId + 3, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12347L, 0.0, 0.0, SideCode.Unknown, (None, None),
        geomNew3, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew3), 0L, 0, 0, reversed = false,
        None, 86400L)

      val otherProjectLinks = Seq(projectLink1, projectLinkNew3)
      val newProjectLinks = Seq(projectLinkNew2)

      val startingPointsForCalculations = defaultSectionCalculatorStrategy.findStartingPoints(newProjectLinks, otherProjectLinks, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
      if (sideCode == SideCode.TowardsDigitizing) {
        startingPointsForCalculations should be((geomNew3.head, geomNew3.head))
      } else {
        startingPointsForCalculations should be((geom1.last, geom1.last))
      }
    }
  }

  /*
        ^
         \  <- #1 New
             (minor discontinuity)
           \  <- #2 New
               (minor discontinuity)
             \  <- #3 New
   */
  test("Test findStartingPoints When adding one (New) link with minor discontinuities before and after the existing (New) links Then the road should still maintain the previous existing direction") {
    testFindStartingPointsWithTwoMinorDiscontinuitiesNewInMiddle(SideCode.TowardsDigitizing, LinkStatus.New)
  }

  /*
         \  <- #1 New
             (minor discontinuity)
           \  <- #2 New
               (minor discontinuity)
             \  <- #3 New
              v
   */
  test("Test findStartingPoints When adding one (New) link with minor discontinuities before and after the existing (New) links (against digitization) Then the road should still maintain the previous existing direction") {
    testFindStartingPointsWithTwoMinorDiscontinuitiesNewInMiddle(SideCode.AgainstDigitizing, LinkStatus.New)
  }

  /*
        ^
         \  <- #1 Transfer
             (minor discontinuity)
           \  <- #2 New
               (minor discontinuity)
             \  <- #3 New
   */
  test("Test findStartingPoints When adding one (New) link with minor discontinuities before and after the existing (New and Transfer) links Then the road should still maintain the previous existing direction") {
    testFindStartingPointsWithTwoMinorDiscontinuitiesNewInMiddle(SideCode.TowardsDigitizing, LinkStatus.Transfer)
  }

  /*
         \  <- #1 Transfer
             (minor discontinuity)
           \  <- #2 New
               (minor discontinuity)
             \  <- #3 New
              v
   */
  test("Test findStartingPoints When adding one (New) link with minor discontinuities before and after the existing (Transfer and New) links (against digitization) Then the road should still maintain the previous existing direction") {
    testFindStartingPointsWithTwoMinorDiscontinuitiesNewInMiddle(SideCode.AgainstDigitizing, LinkStatus.Transfer)
  }

  /*
        ^
         \  <- #1 NotHandled
             (minor discontinuity)
           \  <- #2 New
               (minor discontinuity)
             \  <- #3 New
   */
  test("Test findStartingPoints When adding one (New) link with minor discontinuities before and after the existing (New and NotHandled) links Then the road should still maintain the previous existing direction") {
    testFindStartingPointsWithTwoMinorDiscontinuitiesNewInMiddle(SideCode.TowardsDigitizing, LinkStatus.NotHandled)
  }

  /*
         \  <- #1 NotHandled
             (minor discontinuity)
           \  <- #2 New
               (minor discontinuity)
             \  <- #3 New
              v
   */
  test("Test findStartingPoints When adding one (New) link with minor discontinuities before and after the existing (NotHandled and New) links (against digitization) Then the road should still maintain the previous existing direction") {
    testFindStartingPointsWithTwoMinorDiscontinuitiesNewInMiddle(SideCode.AgainstDigitizing, LinkStatus.NotHandled)
  }

  /*
       \  <- #1
           (minor discontinuity)
         \  <- #2
             (minor discontinuity)
          \ \  <- #3/#4
   */
  private def testFindStartingPointsWithTwoMinorDiscontinuitiesNewInMiddleTwoTrackOnOtherSide(sideCode: SideCode, linkStatus: LinkStatus): Unit = {
    runWithRollback {
      val geom1 = Seq(Point(10.0, 40.0), Point(0.0, 50.0))
      val geom3 = Seq(Point(45.0, 0.0), Point(35.0, 10.0))
      val geom4 = Seq(Point(55.0, 0.0), Point(45.0, 10.0))
      val plId = Sequences.nextProjectLinkId

      val startAddr1 = if (sideCode == SideCode.TowardsDigitizing) 15L else 0
      val endAddr1 = if (sideCode == SideCode.TowardsDigitizing) 30L else 15L
      val projectLink1 = ProjectLink(plId + 1, 9999L, 1L, Track.Combined, Discontinuity.Continuous, startAddr1, endAddr1, startAddr1, endAddr1, None, None,
        None, 12345L, 0.0, 15.0, sideCode, (None, None),
        geom1, 0L, linkStatus, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom1), 0L, 0, 0, reversed = false,
        None, 86400L)

      val startAddr34 = if (sideCode == SideCode.TowardsDigitizing) 0L else 15L
      val endAddr34 = if (sideCode == SideCode.TowardsDigitizing) 15L else 30L
      val projectLinkNew3 = ProjectLink(plId + 3, 9999L, 1L, if (sideCode == SideCode.TowardsDigitizing) Track.LeftSide else Track.RightSide, Discontinuity.Continuous, startAddr34, endAddr34, startAddr34, endAddr34, None, None,
        None, 12347L, 0.0, 0.0, sideCode, (None, None),
        geom3, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom3), 0L, 0, 0, reversed = false,
        None, 86400L)
      val projectLinkNew4 = ProjectLink(plId + 4, 9999L, 1L, if (sideCode == SideCode.TowardsDigitizing) Track.RightSide else Track.LeftSide, Discontinuity.Continuous, startAddr34, endAddr34, startAddr34, endAddr34, None, None,
        None, 12348L, 0.0, 0.0, sideCode, (None, None),
        geom4, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom4), 0L, 0, 0, reversed = false,
        None, 86400L)

      val geomNew2 = Seq(Point(30.0, 20.0), Point(20.0, 30.0))

      // Notice that discontinuity value should not affect calculations, which are based on geometry. That's why we have here this value "Continuous".
      val projectLinkNew2 = ProjectLink(plId + 2, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12346L, 0.0, 0.0, SideCode.Unknown, (None, None),
        geomNew2, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew2), 0L, 0, 0, reversed = false,
        None, 86400L)

      val otherProjectLinks = Seq(projectLink1, projectLinkNew3, projectLinkNew4)
      val newProjectLinks = Seq(projectLinkNew2)

      val startingPointsForCalculations = defaultSectionCalculatorStrategy.findStartingPoints(newProjectLinks, otherProjectLinks, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
      if (sideCode == SideCode.TowardsDigitizing) {
        startingPointsForCalculations should be((geom4.head, geom3.head))
      } else {
        startingPointsForCalculations should be((geom1.last, geom1.last))
      }
    }
  }

  /*
      ^
       \  <- #1 NotHandled
           (minor discontinuity)
         \  <- #2 New
             (minor discontinuity)
          \ \  <- #3/#4 New
   */
  test("Test findStartingPoints When adding one (New) link with minor discontinuities before and after the existing (New two track and NotHandled) road Then the road should still maintain the previous existing direction") {
    testFindStartingPointsWithTwoMinorDiscontinuitiesNewInMiddleTwoTrackOnOtherSide(SideCode.TowardsDigitizing, LinkStatus.NotHandled)
  }

  /*
       \  <- #1 NotHandled
           (minor discontinuity)
         \  <- #2 New
             (minor discontinuity)
          \ \  <- #3/#4 New
           v v
   */
  test("Test findStartingPoints When adding one (New) link with minor discontinuities before and after the existing (NotHandled and New two track) road (against digitizing) Then the road should still maintain the previous existing direction") {
    testFindStartingPointsWithTwoMinorDiscontinuitiesNewInMiddleTwoTrackOnOtherSide(SideCode.AgainstDigitizing, LinkStatus.NotHandled)
  }

  /*
      ^
       \  <- #1 New
           (minor discontinuity)
         \  <- #2 New
             (minor discontinuity)
          \ \  <- #3/#4 New
   */
  test("Test findStartingPoints When adding one (New) link with minor discontinuities before and after the existing (New two track and New) road Then the road should still maintain the previous existing direction") {
    testFindStartingPointsWithTwoMinorDiscontinuitiesNewInMiddleTwoTrackOnOtherSide(SideCode.TowardsDigitizing, LinkStatus.New)
  }

  /*
       \  <- #1 New
           (minor discontinuity)
         \  <- #2 New
             (minor discontinuity)
          \ \  <- #3/#4 New
           v v
   */
  test("Test findStartingPoints When adding one (New) link with minor discontinuities before and after the existing (New and New two track) road (against digitizing) Then the road should still maintain the previous existing direction") {
    testFindStartingPointsWithTwoMinorDiscontinuitiesNewInMiddleTwoTrackOnOtherSide(SideCode.AgainstDigitizing, LinkStatus.New)
  }

  /*
      ^
       \  <- #1 Transfer
           (minor discontinuity)
         \  <- #2 New
             (minor discontinuity)
          \ \  <- #3/#4 New
   */
  test("Test findStartingPoints When adding one (New) link with minor discontinuities before and after the existing (New two track and Transfer) road Then the road should still maintain the previous existing direction") {
    testFindStartingPointsWithTwoMinorDiscontinuitiesNewInMiddleTwoTrackOnOtherSide(SideCode.TowardsDigitizing, LinkStatus.Transfer)
  }

  /*
       \  <- #1 Transfer
           (minor discontinuity)
         \  <- #2 New
             (minor discontinuity)
          \ \  <- #3/#4 New
           v v
   */
  test("Test findStartingPoints When adding one (New) link with minor discontinuities before and after the existing (Transfer and New two track) road (against digitizing) Then the road should still maintain the previous existing direction") {
    testFindStartingPointsWithTwoMinorDiscontinuitiesNewInMiddleTwoTrackOnOtherSide(SideCode.AgainstDigitizing, LinkStatus.Transfer)
  }

  /*
                ^
                 \   <- #1 Transfer
                      (minor discontinuity)
       #3 New ->  \ \  <- #2 New
   */
  test("Test findStartingPoints When adding two track road (New) with minor discontinuity before the existing (Transfer) road Then the road should still maintain the previous existing direction") {
    runWithRollback {
      val geomTransfer1 = Seq(Point(10.0, 20.0), Point(0.0, 30.0))
      val plId = Sequences.nextProjectLinkId

      val projectLink1 = ProjectLink(plId + 1, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 15L, 0L, 15L, None, None,
        None, 12345L, 0.0, 15.0, SideCode.TowardsDigitizing, (None, None),
        geomTransfer1, 0L, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransfer1), 0L, 0, 0, reversed = false,
        None, 86400L)

      val geomNew2 = Seq(Point(35.0, 0.0), Point(25.0, 10.0))
      val geomNew3 = Seq(Point(25.0, 0.0), Point(15.0, 10.0))

      val projectLinkNew2 = ProjectLink(plId + 2, 9999L, 1L, Track.RightSide, Discontinuity.MinorDiscontinuity, 0L, 0L, 0L, 0L, None, None,
        None, 12347L, 0.0, 0.0, SideCode.Unknown, (None, None),
        geomNew2, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew2), 0L, 0, 0, reversed = false,
        None, 86400L)
      val projectLinkNew3 = ProjectLink(plId + 3, 9999L, 1L, Track.LeftSide, Discontinuity.MinorDiscontinuity, 0L, 0L, 0L, 0L, None, None,
        None, 12347L, 0.0, 0.0, SideCode.Unknown, (None, None),
        geomNew3, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew3), 0L, 0, 0, reversed = false,
        None, 86400L)


      val otherProjectLinks = Seq(projectLink1)
      val newProjectLinks = Seq(projectLinkNew2, projectLinkNew3)

      val startingPointsForCalculations = defaultSectionCalculatorStrategy.findStartingPoints(newProjectLinks, otherProjectLinks, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
      startingPointsForCalculations should be((geomNew2.head, geomNew3.head))
    }
  }

  test("Test findStartingPoints When adding two (New) links before and after existing transfer links(s) Then the road should maintain the previous direction") {
    runWithRollback {
      val geomTransferComb1 = Seq(Point(40.0, 30.0), Point(30.0, 40.0))
      val geomTransferComb2 = Seq(Point(30.0, 40.0), Point(20.0, 50.0))
      val plId = Sequences.nextProjectLinkId

      val projectLinkComb1 = ProjectLink(plId + 1, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 15L, 30L, 15L, 30L, None, None,
        None, 12345L, 0.0, 15.0, SideCode.TowardsDigitizing, (None, None),
        geomTransferComb1, 0L, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferComb1), 0L, 0, 0, reversed = false,
        None, 86400L)
      val projectLinkComb2 = ProjectLink(plId + 2, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 30L, 45L, 30L, 45L, None, None,
        None, 12346L, 0.0, 15.0, SideCode.TowardsDigitizing, (None, None),
        geomTransferComb2, 0L, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferComb2), 0L, 0, 0, reversed = false,
        None, 86400L)

      val geomNewCombBefore = Seq(Point(50.0, 20.0), Point(40.0, 30.0))
      val geomNewCombAfter = Seq(Point(10.0, 60.0), Point(20.0, 60.0))

      val projectLinkCombNewBefore = ProjectLink(plId + 3, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12347L, 0.0, 0.0, SideCode.Unknown, (None, None),
        geomNewCombBefore, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewCombBefore), 0L, 0, 0, reversed = false,
        None, 86400L)

      val projectLinkCombNewAfter = ProjectLink(plId + 4, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12347L, 0.0, 0.0, SideCode.Unknown, (None, None),
        geomNewCombAfter, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewCombAfter), 0L, 0, 0, reversed = false,
        None, 86400L)

      val transferProjectLinks = Seq(projectLinkComb1, projectLinkComb2)
      val newProjectLinks = Seq(projectLinkCombNewBefore, projectLinkCombNewAfter)

      val startingPointsForCalculations = defaultSectionCalculatorStrategy.findStartingPoints(newProjectLinks, transferProjectLinks, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
      startingPointsForCalculations should be((geomNewCombBefore.head, geomNewCombBefore.head))
    }
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
      val geomNewRight1 = Seq(Point(0.0, 0.0), Point(5.0, 0.0))
      val geomNewRight2 = Seq(Point(5.0, 0.0), Point(10.0, 0.0))
      //after roundabout
      val geomTransferRight3 = Seq(Point(20.0, 0.0), Point(19.0, 5.0))
      val geomTransferRight4 = Seq(Point(19.0, 5.0), Point(18.0, 10.0))
      val geomTransferRight5 = Seq(Point(18.0, 10.0), Point(15.0, 25.0))

      val projectId = Sequences.nextViiteProjectId
      val roadwayId = Sequences.nextRoadwayId
      val linearLocationId = Sequences.nextLinearLocationId
      val roadwayNumber = Sequences.nextRoadwayNumber
      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      //projectlinks

      //before roundabout

      //Left Transfer
      val projectLinkLeft1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 5L, 0L, 5L, None, None,
        None, 12345L, 0.0, 5.0, SideCode.TowardsDigitizing, (None, None),
        geomTransferLeft1, projectId, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferLeft1), roadwayId, linearLocationId, 8L, reversed = false,
        None, 86400L, roadwayNumber = roadwayNumber)
      val projectLinkLeft2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.ParallelLink, 5L, 10L, 5L, 10L, None, None,
        None, 12346L, 0.0, 5.0, SideCode.TowardsDigitizing, (None, None),
        geomTransferLeft2, projectId, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferLeft2), roadwayId + 1, linearLocationId + 1, 8L, reversed = false,
        None, 86400L, roadwayNumber = roadwayNumber)
      //Right New
      val projectLinkRight1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12347L, 0.0, 5.0, SideCode.TowardsDigitizing, (None, None),
        geomNewRight1, projectId, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewRight1), 0, 0, 8L, reversed = false,
        None, 86400L)
      val projectLinkRight2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.MinorDiscontinuity, 0L, 0L, 0L, 0L, None, None,
        None, 12348L, 0.0, 5.0, SideCode.TowardsDigitizing, (None, None),
        geomNewRight2, projectId, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewRight2), 0, 0, 8L, reversed = false,
        None, 86400L)

      //after roundabout

      //Left New
      val projectLinkLeft3 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12349L, 0.0, 5.1, SideCode.TowardsDigitizing, (None, None),
        geomTransferLeft3, projectId, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferLeft3), 0, 0, 8L, reversed = false,
        None, 86400L)
      val projectLinkLeft4 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12350L, 0.0, 5.3, SideCode.TowardsDigitizing, (None, None),
        geomTransferLeft4, projectId, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferLeft4), 0, 0, 8L, reversed = false,
        None, 86400L)
      val projectLinkLeft5 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12351L, 0.0, 10.1, SideCode.TowardsDigitizing, (None, None),
        geomTransferLeft5, projectId, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferLeft5), 0, 0, 8L, reversed = false,
        None, 86400L)
      //Right Transfer
      val projectLinkRight3 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 5L, 0L, 5L, None, None,
        None, 12352L, 0.0, 5.1, SideCode.TowardsDigitizing, (None, None),
        geomTransferRight3, projectId, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight3), roadwayId + 2, linearLocationId + 2, 8L, reversed = false,
        None, 86400L, roadwayNumber = Sequences.nextRoadwayNumber)
      val nextRwNumber = Sequences.nextRoadwayNumber
      val projectLinkRight4 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 5L, 10L, 5L, 10L, None, None,
        None, 12352L, 0.0, 5.1, SideCode.TowardsDigitizing, (None, None),
        geomTransferRight4, projectId, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight4), roadwayId + 3, linearLocationId + 3, 8L, reversed = false,
        None, 86400L, roadwayNumber = nextRwNumber)
      val projectLinkRight5 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 15L, 10L, 15L, None, None,
        None, 12353L, 0.0, 15.2, SideCode.TowardsDigitizing, (None, None),
        geomTransferRight5, projectId, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight5), roadwayId + 4, linearLocationId + 4, 8L, reversed = false,
        None, 86400L, roadwayNumber = nextRwNumber)

      //create before transfer data
      val (linearLeft1, rwLeft1): (LinearLocation, Roadway) = Seq(projectLinkLeft1).map(toRoadwayAndLinearLocation).head
      val (linearLeft2, rwLeft2): (LinearLocation, Roadway) = Seq(projectLinkLeft2).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rwLeft1.copy(id = roadwayId, ely = 8L)
      val rw2WithId = rwLeft2.copy(id = roadwayId+1, ely = 8L)
      val linearLeft1WithId = linearLeft1.copy(id = linearLocationId)
      val linearLeft2WithId = linearLeft2.copy(id = linearLocationId+1)

      //create after transfer data
      val (linearRight3, rwRight3): (LinearLocation, Roadway) = Seq(projectLinkRight3).map(toRoadwayAndLinearLocation).head
      val (linearRight4, rwRight4): (LinearLocation, Roadway) = Seq(projectLinkRight4).map(toRoadwayAndLinearLocation).head
      val (linearRight5, rwRight5): (LinearLocation, Roadway) = Seq(projectLinkRight5).map(toRoadwayAndLinearLocation).head
      val rw3WithId = rwRight3.copy(id = roadwayId+2, ely = 8L)
      val rw4WithId = rwRight4.copy(id = roadwayId+3, ely = 8L)
      val rw5WithId = rwRight5.copy(id = roadwayId+4, ely = 8L)
      val linearRight3WithId = linearRight3.copy(id = linearLocationId+2)
      val linearRight4WithId = linearRight4.copy(id = linearLocationId+3)
      val linearRight5WithId = linearRight5.copy(id = linearLocationId+4)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId, rw2WithId, rw3WithId, rw4WithId, rw5WithId)), Some(Seq(linearLeft1WithId, linearLeft2WithId, linearRight3WithId, linearRight4WithId, linearRight5WithId)), None)

      /*  assignMValues before roundabout */
      val assignedValues = defaultSectionCalculatorStrategy.assignMValues(Seq(projectLinkRight1, projectLinkRight2), Seq(projectLinkLeft1, projectLinkLeft2), Seq.empty[UserDefinedCalibrationPoint])

      val (left, right) = assignedValues.partition(_.track == Track.LeftSide)

      left.map(_.roadwayNumber).distinct.size should be (1)
      right.map(_.roadwayNumber).distinct.size should be (left.map(_.roadwayNumber).distinct.size)

      val assignedValues2 = defaultSectionCalculatorStrategy.assignMValues(Seq(projectLinkRight1, projectLinkRight2.copy(roadType = RoadType.PrivateRoadType)), Seq(projectLinkLeft1, projectLinkLeft2.copy(roadwayNumber = Sequences.nextRoadwayNumber, roadType = RoadType.PrivateRoadType)), Seq.empty[UserDefinedCalibrationPoint])

      val (left2, right2) = assignedValues2.partition(_.track == Track.LeftSide)
      //should have same 2 different roadwayNumber since they have 2 different roadtypes (projectLinkLeft2 have now Private RoadType)
      left2.map(_.roadwayNumber).distinct.size should be (2)
      right2.map(_.roadwayNumber).distinct.size should be (left2.map(_.roadwayNumber).distinct.size)

      /*  assignMValues before and after roundabout */
      val assignedValues3 = defaultSectionCalculatorStrategy.assignMValues(Seq(projectLinkLeft3, projectLinkLeft4, projectLinkLeft5), assignedValues++Seq(projectLinkRight3, projectLinkRight4, projectLinkRight5), Seq.empty[UserDefinedCalibrationPoint])

      val (left3, right3) = assignedValues3.partition(_.track == Track.LeftSide)
      left3.map(_.roadwayNumber).distinct.size should be (3)
      right3.map(_.roadwayNumber).distinct.size should be (left3.map(_.roadwayNumber).distinct.size)

      assignedValues3.find(_.linearLocationId == projectLinkRight4.linearLocationId).get.roadwayNumber should be (assignedValues3.find(_.linearLocationId == projectLinkRight5.linearLocationId).get.roadwayNumber)
      assignedValues3.find(_.linearLocationId == projectLinkLeft4.linearLocationId).get.roadwayNumber should be (assignedValues3.find(_.linearLocationId == projectLinkLeft5.linearLocationId).get.roadwayNumber)

      /*  assignMValues after roundabout */
      val assignedValues4 = defaultSectionCalculatorStrategy.assignMValues(Seq(projectLinkLeft3, projectLinkLeft4, projectLinkLeft5), Seq(projectLinkRight3, projectLinkRight4, projectLinkRight5), Seq.empty[UserDefinedCalibrationPoint])

      val (left4, right4) = assignedValues4.partition(_.track == Track.LeftSide)
      left4.map(_.roadwayNumber).distinct.size should be (2)
      right4.map(_.roadwayNumber).distinct.size should be (left4.map(_.roadwayNumber).distinct.size)

    }
  }

  test("Test defaultSectionCalculatorStrategy.assignMValues() and the attribution of roadway_numbers for new Left Right sections with diff number of links Then " +
    "if there are for e.g. 2 (two) consecutive links with diff roadway number (and all Transfer status), the opposite track (New), should also have 2 diff roadway numbers link(s) even if the amount of the new links is bigger than the Transfer side") {
    runWithRollback {

      /*geoms
         ^  ^
         |  |
Left     ^  ^   Right
         |  |
         ^  |
         |  |
          ^
          | Combined
      */

      //Combined
      val geomTransferCombined1 = Seq(Point(10.0, 0.0), Point(10.0, 5.0))
      val geomTransferCombined2 = Seq(Point(10.0, 5.0), Point(10.0, 10.0))

      //Left
      val geomNewLeft1 = Seq(Point(10.0, 10.0), Point(8.0, 13.0))
      val geomNewLeft2 = Seq(Point(8.0, 13.0), Point(6.0, 16.0))
      val geomNewLeft3 = Seq(Point(6.0, 16.0), Point(5.0, 20.0))

      //Right
      val geomTransferRight1 = Seq(Point(10.0, 10.0), Point(15.0, 23.0))
      val geomTransferRight2 = Seq(Point(15.0, 23.0), Point(15.0, 30.0))


      val projectId = Sequences.nextViiteProjectId
      val roadwayId1 = Sequences.nextRoadwayId
      val roadwayId2 = Sequences.nextRoadwayId
      val roadwayId3 = Sequences.nextRoadwayId
      val roadwayNumber1 = Sequences.nextRoadwayNumber
      val roadwayNumber2 = Sequences.nextRoadwayNumber
      val roadwayNumber3 = Sequences.nextRoadwayNumber
      val linearLocationId1 = Sequences.nextLinearLocationId
      val linearLocationId2 = Sequences.nextLinearLocationId
      val linearLocationId3 = Sequences.nextLinearLocationId
      val linearLocationId4 = Sequences.nextLinearLocationId
      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      //projectlinks

      //Combined Transfer
      val projectLinkCombined1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 5L, 0L, 5L, None, None,
        None, 12345L, 0.0, 5.0, SideCode.TowardsDigitizing, (None, None),
        geomTransferCombined1, projectId, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferCombined1), roadwayId1, linearLocationId1, 8L, reversed = false,
        None, 86400L, roadwayNumber = roadwayNumber1)

      val projectLinkCombined2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 5L, 10L, 5L, 10L, None, None,
        None, 12346L, 0.0, 5.0, SideCode.TowardsDigitizing, (None, None),
        geomTransferCombined2, projectId, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferCombined2), roadwayId2, linearLocationId2, 8L, reversed = false,
        None, 86400L, roadwayNumber = roadwayNumber2)


      //Left New
      val projectLinkLeft1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Discontinuous, 0L, 0L, 0L, 0L, None, None,
        None, 12348L, 0.0, 3.0, SideCode.TowardsDigitizing, (None, None),
        geomNewLeft1, projectId, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewLeft1), -1000, -1000, 8L, reversed = false,
        None, 86400L)
      val projectLinkLeft2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Discontinuous, 0L, 0L, 0L, 0L, None, None,
        None, 12349L, 0.0, 6.0, SideCode.TowardsDigitizing, (None, None),
        geomNewLeft2, projectId, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewLeft2), -1000, -1000, 8L, reversed = false,
        None, 86400L)
      val projectLinkLeft3 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Discontinuous, 0L, 0L, 0L, 0L, None, None,
        None, 12350L, 0.0, 10.0, SideCode.TowardsDigitizing, (None, None),
        geomNewLeft3, projectId, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewLeft3), -1000, -1000, 8L, reversed = false,
        None, 86400L)

      //Right Transfer
      val projectLinkRight1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 10L, 23L, 10L, 23L, None, None,
        None, 12346L, 5.0, 18.0, SideCode.TowardsDigitizing, (None, None),
        geomTransferRight1, projectId, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight1), roadwayId2, linearLocationId3, 8L, reversed = false,
        None, 86400L, roadwayNumber = roadwayNumber2)
      val projectLinkRight2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Discontinuous, 23L, 30, 23L, 30L, None, None,
        None, 12347L, 0.0, 7.0, SideCode.TowardsDigitizing, (None, None),
        geomTransferRight2, projectId, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight2), roadwayId3, linearLocationId4, 8L, reversed = false,
        None, 86400L, roadwayNumber = roadwayNumber3)

      //create before transfer data
      val roadwayCombined1 =  Roadway(roadwayId1, roadwayNumber1, 9999L,1, RoadType.PublicRoad, Track.apply(0), Discontinuity.Continuous, 0, 5, reversed = false, DateTime.now(), None,
        "tester", Some("rd 9999"), 8L, TerminationCode.NoTermination, DateTime.now(), None)
      val linearCombined1 = LinearLocation(linearLocationId1, 1, 12345L, 0.0, 5.0, SideCode.TowardsDigitizing, 86400L,
        (CalibrationPointsUtils.toCalibrationPointReference(Some(ProjectLinkCalibrationPoint(12345L, 0.0, 0))),
          CalibrationPointsUtils.toCalibrationPointReference(None)),
        geomTransferCombined1, LinkGeomSource.NormalLinkInterface,
        roadwayNumber1, Some(DateTime.now().minusDays(1)), None)

      val roadwayCombined2 = Roadway(roadwayId2, roadwayNumber2, 9999L,1, RoadType.PublicRoad, Track.apply(0), Discontinuity.Continuous, 5, 18, reversed = false, DateTime.now(), None,
        "tester", Some("rd 9999"), 8L, TerminationCode.NoTermination, DateTime.now(), None)
      val linearCombined2 = LinearLocation(linearLocationId2, 1, 12346L, 0.0, 5.0, SideCode.TowardsDigitizing, 86400L,
        (CalibrationPointsUtils.toCalibrationPointReference(None),
          CalibrationPointsUtils.toCalibrationPointReference(None)),
        geomTransferCombined2, LinkGeomSource.NormalLinkInterface,
        roadwayNumber2, Some(DateTime.now().minusDays(1)), None)
      val linearCombined3 = LinearLocation(linearLocationId3, 2, 12346L, 5.0, 18.0, SideCode.TowardsDigitizing, 86400L,
        (CalibrationPointsUtils.toCalibrationPointReference(None),
          CalibrationPointsUtils.toCalibrationPointReference(None)),
        geomTransferRight1, LinkGeomSource.NormalLinkInterface,
        roadwayNumber2, Some(DateTime.now().minusDays(1)), None)

      val roadwayCombined3 = Roadway(roadwayId3, roadwayNumber3, 9999L,1, RoadType.PublicRoad, Track.apply(0), Discontinuity.Discontinuous, 0, 7, reversed = false, DateTime.now(), None,
        "tester", Some("rd 9999"), 8L, TerminationCode.NoTermination, DateTime.now(), None)
      val linearCombined4 = LinearLocation(linearLocationId4, 1, 12347L, 0.0, 7.0, SideCode.TowardsDigitizing, 86400L,
        (CalibrationPointsUtils.toCalibrationPointReference(None),
          CalibrationPointsUtils.toCalibrationPointReference(Some(ProjectLinkCalibrationPoint(12347L, 7.0, 30)))),
        geomTransferRight2, LinkGeomSource.NormalLinkInterface,
        roadwayNumber3, Some(DateTime.now().minusDays(1)), None)

      buildTestDataForProject(Some(project), Some(Seq(roadwayCombined1, roadwayCombined2, roadwayCombined3)), Some(Seq(linearCombined1, linearCombined2, linearCombined3, linearCombined4)), None)
      val assignedValues = defaultSectionCalculatorStrategy.assignMValues(Seq(projectLinkLeft1, projectLinkLeft2, projectLinkLeft3), Seq(projectLinkCombined1, projectLinkCombined2, projectLinkRight1, projectLinkRight2), Seq.empty[UserDefinedCalibrationPoint])

      val (left, right) = assignedValues.filterNot(_.track == Track.Combined).partition(_.track == Track.LeftSide)
      assignedValues.size should be (7)
      left.map(_.roadwayNumber).distinct.size should be (right.map(_.roadwayNumber).size)
      left.map(_.roadwayNumber).distinct.size should be (2)
      left.head.startAddrMValue should be (right.head.startAddrMValue)
      left.head.endAddrMValue should be (right.head.endAddrMValue)
      left.last.startAddrMValue should be (right.last.startAddrMValue)
      left.last.endAddrMValue should be (right.last.endAddrMValue)
    }
  }

  test("Test defaultSectionCalculatorStrategy.assignMValues() and the attribution of roadway_numbers for new Left Right sections with diff number of links Then " +
    "if there are for e.g. 2 (two) consecutive links with diff roadway number (and all Transfer status), the opposite track (New), should split their link(s) if the amount of links is lower than two, to have the same amount of roadway numbers") {
    runWithRollback {

      /*geoms
         ^  ^
         |  |
Left     |  ^   Right
         |  |
         |  |
          ^
          | Combined
          ^
          |
      */

      //Combined
      val geomTransferCombined1 = Seq(Point(10.0, 0.0), Point(10.0, 5.0))
      val geomTransferCombined2 = Seq(Point(10.0, 5.0), Point(10.0, 10.0))

      //Left
      val geomNewLeft1 = Seq(Point(10.0, 10.0), Point(5.0, 20.0))

      //Right
      val geomTransferRight1 = Seq(Point(10.0, 10.0), Point(15.0, 23.0))
      val geomTransferRight2 = Seq(Point(15.0, 23.0), Point(15.0, 30.0))


      val projectId = Sequences.nextViiteProjectId
      val roadwayId1 = Sequences.nextRoadwayId
      val roadwayId2 = Sequences.nextRoadwayId
      val roadwayId3 = Sequences.nextRoadwayId
      val roadwayNumber1 = Sequences.nextRoadwayNumber
      val roadwayNumber2 = Sequences.nextRoadwayNumber
      val roadwayNumber3 = Sequences.nextRoadwayNumber
      val linearLocationId1 = Sequences.nextLinearLocationId
      val linearLocationId2 = Sequences.nextLinearLocationId
      val linearLocationId3 = Sequences.nextLinearLocationId
      val linearLocationId4 = Sequences.nextLinearLocationId
      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      //projectlinks

      //Combined Transfer
      val projectLinkCombined1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 5L, 0L, 5L, None, None,
        None, 12345L, 0.0, 5.0, SideCode.TowardsDigitizing, (None, None),
        geomTransferCombined1, projectId, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferCombined1), roadwayId1, linearLocationId1, 8L, reversed = false,
        None, 86400L, roadwayNumber = roadwayNumber1)

      val projectLinkCombined2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 5L, 10L, 5L, 10L, None, None,
        None, 12346L, 0.0, 5.0, SideCode.TowardsDigitizing, (None, None),
        geomTransferCombined2, projectId, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferCombined2), roadwayId2, linearLocationId2, 8L, reversed = false,
        None, 86400L, roadwayNumber = roadwayNumber2)


      //Left New
      val projectLinkLeft1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Discontinuous, 0L, 0L, 0L, 0L, None, None,
        None, 12348L, 0.0, 20.0, SideCode.TowardsDigitizing, (None, None),
        geomNewLeft1, projectId, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewLeft1), -1000, -1000, 8L, reversed = false,
        None, 86400L)

      //Right Transfer
      val projectLinkRight1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 10L, 23L, 10L, 23L, None, None,
        None, 12346L, 5.0, 18.0, SideCode.TowardsDigitizing, (None, None),
        geomTransferRight1, projectId, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight1), roadwayId2, linearLocationId3, 8L, reversed = false,
        None, 86400L, roadwayNumber = roadwayNumber2)
      val projectLinkRight2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Discontinuous, 23L, 30, 23L, 30L, None, None,
        None, 12347L, 0.0, 5.0, SideCode.TowardsDigitizing, (None, None),
        geomTransferRight2, projectId, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight2), roadwayId3, linearLocationId4, 8L, reversed = false,
        None, 86400L, roadwayNumber = roadwayNumber3)

      //create before transfer data
      val roadwayCombined1 =  Roadway(roadwayId1, roadwayNumber1, 9999L,1, RoadType.PublicRoad, Track.apply(0), Discontinuity.Continuous, 0, 5, reversed = false, DateTime.now(), None,
        "tester", Some("rd 9999"), 8L, TerminationCode.NoTermination, DateTime.now(), None)
      val linearCombined1 = LinearLocation(linearLocationId1, 1, 12345L, 0.0, 5.0, SideCode.TowardsDigitizing, 86400L,
        (CalibrationPointsUtils.toCalibrationPointReference(Some(ProjectLinkCalibrationPoint(12345L, 0.0, 0))),
          CalibrationPointsUtils.toCalibrationPointReference(None)),
        geomTransferCombined1, LinkGeomSource.NormalLinkInterface,
        roadwayNumber1, Some(DateTime.now().minusDays(1)), None)

      val roadwayCombined2 = Roadway(roadwayId2, roadwayNumber2, 9999L,1, RoadType.PublicRoad, Track.apply(0), Discontinuity.Continuous, 5, 18, reversed = false, DateTime.now(), None,
        "tester", Some("rd 9999"), 8L, TerminationCode.NoTermination, DateTime.now(), None)
      val linearCombined2 = LinearLocation(linearLocationId2, 1, 12346L, 0.0, 5.0, SideCode.TowardsDigitizing, 86400L,
        (CalibrationPointsUtils.toCalibrationPointReference(None),
          CalibrationPointsUtils.toCalibrationPointReference(None)),
        geomTransferCombined2, LinkGeomSource.NormalLinkInterface,
        roadwayNumber2, Some(DateTime.now().minusDays(1)), None)
      val linearCombined3 = LinearLocation(linearLocationId3, 2, 12346L, 5.0, 18.0, SideCode.TowardsDigitizing, 86400L,
        (CalibrationPointsUtils.toCalibrationPointReference(None),
          CalibrationPointsUtils.toCalibrationPointReference(None)),
        geomTransferRight1, LinkGeomSource.NormalLinkInterface,
        roadwayNumber2, Some(DateTime.now().minusDays(1)), None)

      val roadwayCombined3 = Roadway(roadwayId3, roadwayNumber3, 9999L,1, RoadType.PublicRoad, Track.apply(0), Discontinuity.Discontinuous, 0, 7, reversed = false, DateTime.now(), None,
        "tester", Some("rd 9999"), 8L, TerminationCode.NoTermination, DateTime.now(), None)
      val linearCombined4 = LinearLocation(linearLocationId4, 1, 12347L, 0.0, 7.0, SideCode.TowardsDigitizing, 86400L,
        (CalibrationPointsUtils.toCalibrationPointReference(None),
          CalibrationPointsUtils.toCalibrationPointReference(Some(ProjectLinkCalibrationPoint(12347L, 7.0, 30)))),
        geomTransferRight2, LinkGeomSource.NormalLinkInterface,
        roadwayNumber3, Some(DateTime.now().minusDays(1)), None)

      buildTestDataForProject(Some(project), Some(Seq(roadwayCombined1, roadwayCombined2, roadwayCombined3)), Some(Seq(linearCombined1, linearCombined2, linearCombined3, linearCombined4)), None)
      val assignedValues = defaultSectionCalculatorStrategy.assignMValues(Seq(projectLinkLeft1), Seq(projectLinkCombined1, projectLinkCombined2, projectLinkRight1, projectLinkRight2), Seq.empty[UserDefinedCalibrationPoint])

      val (left, right) = assignedValues.filterNot(_.track == Track.Combined).partition(_.track == Track.LeftSide)
      assignedValues.size should be (6)
      left.map(_.roadwayNumber).distinct.size should be (right.map(_.roadwayNumber).size)
      left.map(_.roadwayNumber).distinct.size should be (2)
      left.head.startAddrMValue should be (right.head.startAddrMValue)
      left.head.endAddrMValue should be (right.head.endAddrMValue)
      left.last.startAddrMValue should be (right.last.startAddrMValue)
      left.last.endAddrMValue should be (right.last.endAddrMValue)
    }
  }

  test("Test defaultSectionCalculatorStrategy.assignMValues() and the attribution of roadway_numbers for new Left Right sections with diff number of links Then " +
    "if there are some consecutive links with diff roadway_number (and all Transfer status), the opposite track, should split their link(s) if the amount of links is lower than the Transfer track side with same end addresses as the other side and different roadway numbers") {
    runWithRollback {

      /*geoms

                 ^ Combined2
                 |
Left3 ----------^^  Right4
      |          |
      |          ^   Right3
Left2 ^          |
      |          ^  Right2
      |         |
Left1 ---<----  ^ Right1
             |  |
              ^
              | Combined1
      */

      //Combined
      val geomTransferCombined1 = Seq(Point(10.0, 0.0), Point(10.0, 5.0))
      val geomTransferCombined2 = Seq(Point(12.0, 20.0), Point(12.0, 25.0))

      //Left
      val geomNewLeft1 = Seq(Point(10.0, 5.0), Point(8.0, 10.0), Point(5.0, 10.0))
      val geomNewLeft2 = Seq(Point(5.0, 10.0), Point(0.0, 10.0), Point(0.0, 15.0))
      val geomNewLeft3 = Seq(Point(0.0, 15.0), Point(0.0, 20.0), Point(12.0, 20.0))

      //Right
      val geomTransferRight1 = Seq(Point(10.0, 5.0), Point(12.0, 10.0))
      val geomTransferRight2 = Seq(Point(12.0, 10.0), Point(12.0, 13.0))
      val geomTransferRight3 = Seq(Point(12.0, 13.0), Point(12.0, 16.0))
      val geomTransferRight4 = Seq(Point(12.0, 16.0), Point(12.0, 20.0))


      val projectId = Sequences.nextViiteProjectId
      val roadwayId1 = Sequences.nextRoadwayId
      val roadwayId2 = Sequences.nextRoadwayId
      val roadwayNumber1 = Sequences.nextRoadwayNumber
      val roadwayNumber2 = Sequences.nextRoadwayNumber
      val linearLocationId1 = Sequences.nextLinearLocationId
      val linearLocationId2 = Sequences.nextLinearLocationId
      val linearLocationId3 = Sequences.nextLinearLocationId
      val linearLocationId4 = Sequences.nextLinearLocationId
      val linearLocationId5 = Sequences.nextLinearLocationId
      val linearLocationId6 = Sequences.nextLinearLocationId
      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      //projectlinks

      //Combined Transfer
      val projectLinkCombined1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 5L, 0L, 5L, None, None,
        None, 12345L, 0.0, 5.0, SideCode.TowardsDigitizing, (None, None),
        geomTransferCombined1, projectId, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferCombined1), roadwayId1, linearLocationId1, 8L, reversed = false,
        None, 86400L, roadwayNumber = roadwayNumber1)

      val projectLinkCombined2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.EndOfRoad, 20L, 25L, 20L, 25L, None, None,
        None, 12349L, 0.0, 5.0, SideCode.TowardsDigitizing, (None, None),
        geomTransferCombined2, projectId, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferCombined2), roadwayId2, linearLocationId6, 8L, reversed = false,
        None, 86400L, roadwayNumber = roadwayNumber2)

      //Left New
      val projectLinkLeft1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12350L, 0.0, 20.0, SideCode.TowardsDigitizing, (None, None),
        geomNewLeft1, projectId, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewLeft1), -1000, -1000, 8L, reversed = false,
        None, 86400L)
      val projectLinkLeft2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12351L, 0.0, 20.0, SideCode.TowardsDigitizing, (None, None),
        geomNewLeft2, projectId, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewLeft2), -1000, -1000, 8L, reversed = false,
        None, 86400L)
      val projectLinkLeft3 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12352L, 0.0, 20.0, SideCode.TowardsDigitizing, (None, None),
        geomNewLeft3, projectId, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewLeft3), -1000, -1000, 8L, reversed = false,
        None, 86400L)

      //Right Transfer
      val projectLinkRight1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 5L, 10L, 5L, 10L, None, None,
        None, 12346L, 0.0, 5.0, SideCode.TowardsDigitizing, (None, None),
        geomTransferRight1, projectId, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight1), roadwayId1, linearLocationId2, 8L, reversed = false,
        None, 86400L, roadwayNumber = roadwayNumber1)
      val projectLinkRight2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 10L, 13, 10L, 13L, None, None,
        None, 12347L, 3.0, 6.0, SideCode.TowardsDigitizing, (None, None),
        geomTransferRight2, projectId, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight2), roadwayId1, linearLocationId3, 8L, reversed = false,
        None, 86400L, roadwayNumber = roadwayNumber1)
      val projectLinkRight3 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 13L, 16L, 13L, 16L, None, None,
        None, 12347L, 0.0, 3.0, SideCode.TowardsDigitizing, (None, None),
        geomTransferRight3, projectId, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight3), roadwayId2, linearLocationId4, 8L, reversed = false,
        None, 86400L, roadwayNumber = roadwayNumber2)
      val projectLinkRight4 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 16L, 20L, 16L, 20L, None, None,
        None, 12348L, 0.0, 4.0, SideCode.TowardsDigitizing, (None, None),
        geomTransferRight4, projectId, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight4), roadwayId2, linearLocationId5, 8L, reversed = false,
        None, 86400L, roadwayNumber = roadwayNumber2)

      //create before transfer data
      val roadwayCombined1 =  Roadway(roadwayId1, roadwayNumber1, 9999L,1, RoadType.PublicRoad, Track.apply(0), Discontinuity.Continuous, 0, 13, reversed = false, DateTime.now(), None,
        "tester", Some("rd 9999"), 8L, TerminationCode.NoTermination, DateTime.now(), None)
      val linearCombined1 = LinearLocation(linearLocationId1, 1, 12345L, 0.0, 5.0, SideCode.TowardsDigitizing, 86400L,
        (CalibrationPointsUtils.toCalibrationPointReference(Some(ProjectLinkCalibrationPoint(12345L, 0.0, 0))),
          CalibrationPointsUtils.toCalibrationPointReference(None)),
        geomTransferCombined1, LinkGeomSource.NormalLinkInterface,
        roadwayNumber1, Some(DateTime.now().minusDays(1)), None)
      val linearCombined2 = LinearLocation(linearLocationId2, 2, 12346L, 0.0, 5.0, SideCode.TowardsDigitizing, 86400L,
        (CalibrationPointsUtils.toCalibrationPointReference(None),
          CalibrationPointsUtils.toCalibrationPointReference(None)),
        geomTransferRight1, LinkGeomSource.NormalLinkInterface,
        roadwayNumber1, Some(DateTime.now().minusDays(1)), None)
      val linearCombined3 = LinearLocation(linearLocationId3, 3, 12347L, 3.0, 6.0, SideCode.TowardsDigitizing, 86400L,
        (CalibrationPointsUtils.toCalibrationPointReference(None),
          CalibrationPointsUtils.toCalibrationPointReference(None)),
        geomTransferRight2, LinkGeomSource.NormalLinkInterface,
        roadwayNumber1, Some(DateTime.now().minusDays(1)), None)

      val roadwayCombined2 = Roadway(roadwayId2, roadwayNumber2, 9999L,1, RoadType.PublicRoad, Track.apply(0), Discontinuity.EndOfRoad, 13, 25, reversed = false, DateTime.now(), None,
        "tester", Some("rd 9999"), 8L, TerminationCode.NoTermination, DateTime.now(), None)
      val linearCombined4 = LinearLocation(linearLocationId4, 1, 12347L, 0.0, 3.0, SideCode.TowardsDigitizing, 86400L,
        (CalibrationPointsUtils.toCalibrationPointReference(None),
          CalibrationPointsUtils.toCalibrationPointReference(None)),
        geomTransferRight3, LinkGeomSource.NormalLinkInterface,
        roadwayNumber2, Some(DateTime.now().minusDays(1)), None)
      val linearCombined5 = LinearLocation(linearLocationId5, 2, 12348L, 0.0, 4.0, SideCode.TowardsDigitizing, 86400L,
        (CalibrationPointsUtils.toCalibrationPointReference(None),
          CalibrationPointsUtils.toCalibrationPointReference(None)),
        geomTransferRight4, LinkGeomSource.NormalLinkInterface,
        roadwayNumber2, Some(DateTime.now().minusDays(1)), None)
      val linearCombined6 = LinearLocation(linearLocationId6, 3, 12349L, 0.0, 5.0, SideCode.TowardsDigitizing, 86400L,
        (CalibrationPointsUtils.toCalibrationPointReference(None),
          CalibrationPointsUtils.toCalibrationPointReference(Some(ProjectLinkCalibrationPoint(12349L, 5.0, 20)))),
        geomTransferCombined2, LinkGeomSource.NormalLinkInterface,
        roadwayNumber2, Some(DateTime.now().minusDays(1)), None)


      buildTestDataForProject(Some(project), Some(Seq(roadwayCombined1, roadwayCombined2)), Some(Seq(linearCombined1, linearCombined2, linearCombined3, linearCombined4, linearCombined5, linearCombined6)), None)
      val assignedValues = defaultSectionCalculatorStrategy.assignMValues(Seq(projectLinkLeft1, projectLinkLeft2, projectLinkLeft3), Seq(projectLinkCombined1, projectLinkRight1, projectLinkRight2, projectLinkRight3, projectLinkRight4, projectLinkCombined2), Seq.empty[UserDefinedCalibrationPoint])

      val (left, right) = assignedValues.filterNot(_.track == Track.Combined).partition(_.track == Track.LeftSide)
      assignedValues.size should be (9)
      left.map(_.roadwayNumber).distinct.size should be (right.map(_.roadwayNumber).size)
      left.map(_.roadwayNumber).distinct.size should be (2)
      left.head.startAddrMValue should be (right.head.startAddrMValue)
      left.head.endAddrMValue should be (right.head.endAddrMValue)
      left.last.startAddrMValue should be (right.last.startAddrMValue)
      left.last.endAddrMValue should be (right.last.endAddrMValue)
    }
  }

  test("Test findStartingPoints When adding two new left and right track links before new and existing Combined links Then the starting points for the left and right road should be points of Left and Right Tracks and not one from the completely opposite side (where the existing Combined link is)") {
    runWithRollback {
      val geomLeft1 = Seq(Point(0.0, 15.0), Point(5.0, 17.0))
      val geomRight1 = Seq(Point(0.0, 10.0), Point(5.0, 17.0))
      val geomNewComb1 = Seq(Point(10.0, 15.0), Point(5.0, 17.0))//against
      val geomTransferComb1 = Seq(Point(20.0, 5.0), Point(15.0, 10.0))//against
      val geomTransferComb2 = Seq(Point(25.0, 0.0), Point(20.0, 5.0))//against
      val otherPartGeomTransferComb1 = Seq(Point(35.0, 0.0), Point(30.0, 0.0))//against
      val plId = Sequences.nextProjectLinkId


      val projectLinkOtherPartComb1 = ProjectLink(plId + 1, 9999L, 2L, Track.Combined, Discontinuity.Continuous, 0L, 5L, 0L, 5L, None, None,
        None, 12344L, 0.0, 5.0, SideCode.AgainstDigitizing, (None, None),
        otherPartGeomTransferComb1, 0L, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(otherPartGeomTransferComb1), 0L, 0, 0, reversed = false,
        None, 86400L)
      val projectLinkComb1 = ProjectLink(plId + 1, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 5L, 10L, 5L, 10L, None, None,
        None, 12345L, 0.0, 5.0, SideCode.AgainstDigitizing, (None, None),
        geomTransferComb1, 0L, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferComb1), 0L, 0, 0, reversed = false,
        None, 86400L)
      val projectLinkComb2 = ProjectLink(plId + 2, 9999L, 1L, Track.Combined, Discontinuity.MinorDiscontinuity, 10L, 15L, 10L, 15L, None, None,
        None, 12346L, 0.0, 5.0, SideCode.AgainstDigitizing, (None, None),
        geomTransferComb2, 0L, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferComb2), 0L, 0, 0, reversed = false,
        None, 86400L)
      val projectLinkCombNewBefore = ProjectLink(plId + 3, 9999L, 1L, Track.Combined, Discontinuity.MinorDiscontinuity, 0L, 5L, 0L, 5L, None, None,
        None, 12347L, 0.0, 5.0, SideCode.AgainstDigitizing, (None, None),
        geomNewComb1, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewComb1), 0L, 0, 0, reversed = false,
        None, 86400L)
      val projectLinkNewLeft = ProjectLink(plId + 4, 9999L, 1L, Track.LeftSide, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12348L, 0.0, 0.0, SideCode.Unknown, (None, None),
        geomLeft1, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft1), 0L, 0, 0, reversed = false,
        None, 86400L)
      val projectLinkNewRight = ProjectLink(plId + 5, 9999L, 1L, Track.RightSide, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12349L, 0.0, 0.0, SideCode.Unknown, (None, None),
        geomRight1, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight1), 0L, 0, 0, reversed = false,
        None, 86400L)


      val transferProjectLinks = Seq(projectLinkComb1, projectLinkComb2)
      val newProjectLinks = Seq(projectLinkCombNewBefore, projectLinkNewLeft, projectLinkNewRight)
      val otherPartLinks = Seq(projectLinkOtherPartComb1)

      val startingPointsForCalculations = defaultSectionCalculatorStrategy.findStartingPoints(newProjectLinks, transferProjectLinks, otherPartLinks, Seq.empty[UserDefinedCalibrationPoint])
      startingPointsForCalculations should be((projectLinkNewRight.startingPoint, projectLinkNewLeft.startingPoint))
    }
  }

  test("Test findStartingPoints When adding new combined link before existing Unhandled links Then the starting point should be the loose candidate from the new link") {
    runWithRollback {
      val geomNewComb1 = Seq(Point(0.0, 20.0), Point(5.0, 15.0))
      val geomTransferComb1 = Seq(Point(5.0, 15.0), Point(10.0, 10.0))
      val geomTransferComb2 = Seq(Point(10.0, 10.0), Point(15.0, 5.0))
      val plId = Sequences.nextProjectLinkId


      val projectLinkNewComb1Before = ProjectLink(plId, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 0, 0, 0, None, None,
        None, 12344L, 0.0, 5.0, SideCode.TowardsDigitizing, (None, None),
        geomNewComb1, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewComb1), 0L, 0, 0, reversed = false,
        None, 86400L)
      val projectLinkComb1 = ProjectLink(plId + 1, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 5L, 0L, 5L, None, None,
        None, 12345L, 0.0, 5.0, SideCode.TowardsDigitizing, (None, None),
        geomTransferComb1, 0L, LinkStatus.NotHandled, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferComb1), 0L, 0, 0, reversed = false,
        None, 86400L)
      val projectLinkComb2 = ProjectLink(plId + 2, 9999L, 1L, Track.Combined, Discontinuity.EndOfRoad, 5L, 10L, 5L, 10L, None, None,
        None, 12346L, 0.0, 5.0, SideCode.TowardsDigitizing, (None, None),
        geomTransferComb2, 0L, LinkStatus.NotHandled, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferComb2), 0L, 0, 0, reversed = false,
        None, 86400L)

      val transferProjectLinks = Seq(projectLinkComb1, projectLinkComb2)
      val newProjectLinks = Seq(projectLinkNewComb1Before)
      val otherPartLinks = Seq()

      val startingPointsForCalculations = defaultSectionCalculatorStrategy.findStartingPoints(newProjectLinks, transferProjectLinks, otherPartLinks, Seq.empty[UserDefinedCalibrationPoint])
      startingPointsForCalculations should be((projectLinkNewComb1Before.startingPoint, projectLinkNewComb1Before.startingPoint))
    }
  }

  test("Test defaultSectionCalculatorStrategy.findStartingPoint() When transferring existing first link of part 2 to part 1, that is after that last link of part 1 Then the starting point should be the one from first link of part 1 that is not handled and the direction of part 1 should not change") {
    runWithRollback {
      val geomNotHandledComb1Part1 = Seq(Point(0.0, 0.0), Point(5.0, 0.0))
      val geomNotHandledComb2Part1 = Seq(Point(5.0, 0.0), Point(10.0, 0.0))
      val geomTransferComb1Part2ToPart1 = Seq(Point(10.0, 0.0), Point(16.0, 0.0))
      val plId = Sequences.nextProjectLinkId


      val projectLinkNotHandledComb1Part1 = ProjectLink(plId, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 5L, 0L, 5L, None, None,
        None, 12344L, 0.0, 5.0, SideCode.TowardsDigitizing, (None, None),
        geomNotHandledComb1Part1, 0L, LinkStatus.NotHandled, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNotHandledComb1Part1), 0L, 0L, 0L, reversed = false,
        None, 86400L)
      val projectLinkNotHandledComb2Part1 = ProjectLink(plId + 1, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 5L, 10L, 5L, 10L, None, None,
        None, 12345L, 0.0, 5.0, SideCode.TowardsDigitizing, (None, None),
        geomNotHandledComb2Part1, 0L, LinkStatus.NotHandled, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNotHandledComb2Part1), 0L, 0, 0, reversed = false,
        None, 86400L)
      val projectLinkTransferComb1Part2ToPart1 = ProjectLink(plId + 2, 9999L, 1L, Track.Combined, Discontinuity.EndOfRoad, 0L, 6L, 0L, 6L, None, None,
        None, 12346L, 0.0, 6.0, SideCode.TowardsDigitizing, (None, None),
        geomTransferComb1Part2ToPart1, 0L, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferComb1Part2ToPart1), 0L, 0, 0, reversed = false,
        None, 86400L)

      val transferProjectLinks = Seq(projectLinkTransferComb1Part2ToPart1, projectLinkNotHandledComb1Part1, projectLinkNotHandledComb2Part1)
      val newProjectLinks = Seq()
      val otherPartLinks = Seq()

      val startingPointsForCalculations = defaultSectionCalculatorStrategy.findStartingPoints(newProjectLinks, transferProjectLinks, otherPartLinks, Seq.empty[UserDefinedCalibrationPoint])
      startingPointsForCalculations should be((projectLinkNotHandledComb1Part1.startingPoint, projectLinkNotHandledComb1Part1.startingPoint))
    }
  }

  test("Test defaultSectionCalculatorStrategy.findStartingPoint() When transferring existing first link of part 2 to part 1, that is before that first part 1 link Then the starting point should be the one from part2 that is being transferred and the direction of part 1 should not change") {
    runWithRollback {
      val geomTransferComb1Part2ToPart1 = Seq(Point(0.0, 0.0), Point(6.0, 0.0))
      val geomNotHandledComb1Part1 = Seq(Point(6.0, 0.0), Point(11.0, 0.0))
      val geomNotHandledComb2Part1 = Seq(Point(11.0, 0.0), Point(16.0, 0.0))
      val plId = Sequences.nextProjectLinkId


      val projectLinkNotHandledComb1Part1 = ProjectLink(plId, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 5L, 0L, 5L, None, None,
        None, 12344L, 0.0, 5.0, SideCode.TowardsDigitizing, (None, None),
        geomNotHandledComb1Part1, 0L, LinkStatus.NotHandled, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNotHandledComb1Part1), 0L, 0L, 0L, reversed = false,
        None, 86400L)
      val projectLinkNotHandledComb2Part1 = ProjectLink(plId + 1, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 5L, 10L, 5L, 10L, None, None,
        None, 12345L, 0.0, 5.0, SideCode.TowardsDigitizing, (None, None),
        geomNotHandledComb2Part1, 0L, LinkStatus.NotHandled, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNotHandledComb2Part1), 0L, 0, 0, reversed = false,
        None, 86400L)
      val projectLinkTransferComb1Part2ToPart1 = ProjectLink(plId + 2, 9999L, 1L, Track.Combined, Discontinuity.EndOfRoad, 0L, 6L, 0L, 6L, None, None,
        None, 12346L, 0.0, 6.0, SideCode.TowardsDigitizing, (None, None),
        geomTransferComb1Part2ToPart1, 0L, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferComb1Part2ToPart1), 0L, 0, 0, reversed = false,
        None, 86400L)

      val transferProjectLinks = Seq(projectLinkTransferComb1Part2ToPart1, projectLinkNotHandledComb1Part1, projectLinkNotHandledComb2Part1)
      val newProjectLinks = Seq()
      val otherPartLinks = Seq()

      val startingPointsForCalculations = defaultSectionCalculatorStrategy.findStartingPoints(newProjectLinks, transferProjectLinks, otherPartLinks, Seq.empty[UserDefinedCalibrationPoint])
      startingPointsForCalculations should be((projectLinkTransferComb1Part2ToPart1.startingPoint, projectLinkTransferComb1Part2ToPart1.startingPoint))
    }
  }

  /*
                     |   <- New #2 (One more link added in the beginning)
                     |   <- New #1 (Against digitization)
                     v
   */
  test("Test findStartingPoints When adding one (New) link before the existing (New) road that goes against the digitization Then the road should still maintain the previous existing direction") {
    runWithRollback {
      val geomNew1 = Seq(Point(0.0, 0.0), Point(0.0, 10.0))
      val plId = Sequences.nextProjectLinkId

      val projectLinkNew1 = ProjectLink(plId, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 10L, 0L, 0L, None, None,
        None, 12344L, 0.0, 10.0, SideCode.AgainstDigitizing, (None, None),
        geomNew1, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew1), 0L, 0, 0, reversed = false,
        None, 86400L)

      val geomNew2 = Seq(Point(0.0, 10.0), Point(0.0, 20.0))

      val projectLinkNew2 = ProjectLink(plId + 1, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12347L, 0.0, 0.0, SideCode.Unknown, (None, None),
        geomNew2, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew2), 0L, 0, 0, reversed = false,
        None, 86400L)

      val otherProjectLinks = Seq(projectLinkNew1)
      val newProjectLinks = Seq(projectLinkNew2)

      val startingPointsForCalculations = defaultSectionCalculatorStrategy.findStartingPoints(newProjectLinks, otherProjectLinks, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
      startingPointsForCalculations should be((geomNew2.last, geomNew2.last))
    }
  }

  /*
                     |   <- New #1 (Against digitization)
                     |   <- New #2 (One more link added at the end)
                     v
   */
  test("Test findStartingPoints When adding one (New) link after the existing (New) road that goes against the digitization Then the road should still maintain the previous existing direction") {
    runWithRollback {
      val geomNew1 = Seq(Point(0.0, 10.0), Point(0.0, 20.0))
      val plId = Sequences.nextProjectLinkId

      val projectLinkNew1 = ProjectLink(plId, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 10L, 0L, 0L, None, None,
        None, 12344L, 0.0, 10.0, SideCode.AgainstDigitizing, (None, None),
        geomNew1, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew1), 0L, 0, 0, reversed = false,
        None, 86400L)

      val geomNew2 = Seq(Point(0.0, 0.0), Point(0.0, 10.0))

      val projectLinkNew2 = ProjectLink(plId + 1, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12347L, 0.0, 0.0, SideCode.Unknown, (None, None),
        geomNew2, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew2), 0L, 0, 0, reversed = false,
        None, 86400L)

      val otherProjectLinks = Seq(projectLinkNew1)
      val newProjectLinks = Seq(projectLinkNew2)

      val startingPointsForCalculations = defaultSectionCalculatorStrategy.findStartingPoints(newProjectLinks, otherProjectLinks, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
      startingPointsForCalculations should be((geomNew1.last, geomNew1.last))
    }
  }

  /*

                      -|-
               C1   /    \  C2
                  |-       -|

  Test specific case for two completely new links i.e. with Unknown side codes and the Combined link number 2  have its geometry against normal geometry grow
  Then the starting point should never be their mutual connecting point, but instead one of the edges.


   */
  test("Test findStartingPoints When adding two completely (New) links both with end addresses 0, unknown side code and with one of them having inverted geometry grow, Then the direction should not be the one starting in the mid.") {
    runWithRollback {
      val geomNew1 = Seq(Point(723.562,44.87,94.7409999999945),
          Point(792.515,54.912,95.8469999999943))
      val plId = Sequences.nextProjectLinkId

      val projectLinkNew1 = ProjectLink(plId, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12344L, 0.0, 9.0, SideCode.Unknown, (None, None),
        geomNew1, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew1), 0L, 0, 0, reversed = false,
        None, 86400L)

      val geomNew2 = Seq(Point(973.346,33.188,93.2029999999941),
        Point(847.231,62.266,94.823000000004),
        Point(792.515,54.912,95.8469999999943))

      val projectLinkNew2 = ProjectLink(plId + 1, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12345L, 0.0, 10.0, SideCode.Unknown, (None, None),
        geomNew2, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew2), 0L, 0, 0, reversed = false,
        None, 86400L)

      val otherProjectLinks = Seq()
      val newProjectLinks = Seq(projectLinkNew1, projectLinkNew2)

      val startingPointsForCalculations = defaultSectionCalculatorStrategy.findStartingPoints(newProjectLinks, otherProjectLinks, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
      startingPointsForCalculations should not be((geomNew2.last, geomNew2.last))
    }
  }

  /*
                               |
                               |    <- New #1 (Against digitization)
                              / \
    New #2 (Right track) ->  |   |  <- New #3 (Left track)
                             v   v
   */
  test("Test findStartingPoints When adding two track road (New) after the existing (New) road that goes against the digitization Then the road should still maintain the previous existing direction") {
    runWithRollback {
      val geomNew1 = Seq(Point(5.0, 10.0), Point(5.0, 20.0))
      val plId = Sequences.nextProjectLinkId

      val projectLinkNew1 = ProjectLink(plId, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 10L, 0L, 0L, None, None,
        None, 12344L, 0.0, 10.0, SideCode.AgainstDigitizing, (None, None),
        geomNew1, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew1), 0L, 0, 0, reversed = false,
        None, 86400L)

      val geomNew2 = Seq(Point(0.0, 0.0), Point(5.0, 10.0))

      val projectLinkNew2 = ProjectLink(plId + 1, 9999L, 1L, Track.RightSide, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12345L, 0.0, 0.0, SideCode.Unknown, (None, None),
        geomNew2, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew2), 0L, 0, 0, reversed = false,
        None, 86400L)

      val geomNew3 = Seq(Point(10.0, 0.0), Point(5.0, 10.0))

      val projectLinkNew3 = ProjectLink(plId + 2, 9999L, 1L, Track.LeftSide, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12346L, 0.0, 0.0, SideCode.Unknown, (None, None),
        geomNew3, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew3), 0L, 0, 0, reversed = false,
        None, 86400L)

      val otherProjectLinks = Seq(projectLinkNew1)
      val newProjectLinks = Seq(projectLinkNew2, projectLinkNew3)

      val startingPointsForCalculations = defaultSectionCalculatorStrategy.findStartingPoints(newProjectLinks, otherProjectLinks, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
      startingPointsForCalculations should be((geomNew1.last, geomNew1.last))
    }
  }

  /*
                               ^
                               |
                               |    <- New #1
                              / \
     New #2 (Left track) ->  |   |  <- New #3 (Right track)
   */
  test("Test findStartingPoints When adding left side of two track road (New) before the existing (New) road Then the road should still maintain the previous existing direction") {
    runWithRollback {
      val geomNew1 = Seq(Point(5.0, 10.0), Point(5.0, 20.0))
      val plId = Sequences.nextProjectLinkId

      val projectLinkNew1 = ProjectLink(plId, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 10L, 0L, 0L, None, None,
        None, 12344L, 0.0, 10.0, SideCode.TowardsDigitizing, (None, None),
        geomNew1, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew1), 0L, 0, 0, reversed = false,
        None, 86400L)

      val geomNew2 = Seq(Point(0.0, 0.0), Point(5.0, 10.0))

      val projectLinkNew2 = ProjectLink(plId + 1, 9999L, 1L, Track.LeftSide, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12345L, 0.0, 0.0, SideCode.Unknown, (None, None),
        geomNew2, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew2), 0L, 0, 0, reversed = false,
        None, 86400L)

      val geomNew3 = Seq(Point(10.0, 0.0), Point(5.0, 10.0))

      val projectLinkNew3 = ProjectLink(plId + 2, 9999L, 1L, Track.RightSide, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12346L, 0.0, 0.0, SideCode.Unknown, (None, None),
        geomNew3, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew3), 0L, 0, 0, reversed = false,
        None, 86400L)

      val otherProjectLinks = Seq(projectLinkNew1, projectLinkNew3)
      val newProjectLinks = Seq(projectLinkNew2)

      val startingPointsForCalculations = defaultSectionCalculatorStrategy.findStartingPoints(newProjectLinks, otherProjectLinks, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
      startingPointsForCalculations should be((geomNew3.head, geomNew2.head))
    }
  }

  /*
       |   <- #2
       |   <- #1
       |   <- #3
   */
  private def testNewExistingNew(statusOfExisting: LinkStatus, sideCode: SideCode): Unit = {
    runWithRollback {
      val geom1 = Seq(Point(0.0, 10.0), Point(0.0, 20.0))

      val projectLink1 = ProjectLink(1, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 10L, 0L, 0L, None, None,
        None, 12344L, 0.0, 10.0, sideCode, (None, None),
        geom1, 0L, statusOfExisting, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom1), 0L, 0, 0, reversed = false,
        None, 86400L)

      val geomNew2 = Seq(Point(0.0, 20.0), Point(0.0, 30.0))
      val geomNew3 = Seq(Point(0.0, 0.0), Point(0.0, 10.0))

      val projectLinkNew2 = ProjectLink(2, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12345L, 0.0, 0.0, SideCode.Unknown, (None, None),
        geomNew2, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew2), 0L, 0, 0, reversed = false,
        None, 86400L)

      val projectLinkNew3 = ProjectLink(3, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12346L, 0.0, 0.0, SideCode.Unknown, (None, None),
        geomNew3, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew3), 0L, 0, 0, reversed = false,
        None, 86400L)

      val otherProjectLinks = Seq(projectLink1)
      val newProjectLinks = Seq(projectLinkNew2, projectLinkNew3)

      val startingPointsForCalculations = defaultSectionCalculatorStrategy.findStartingPoints(newProjectLinks, otherProjectLinks, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
      if (sideCode == SideCode.AgainstDigitizing) {
        startingPointsForCalculations should be((geomNew2.last, geomNew2.last))
      } else {
        startingPointsForCalculations should be((geomNew3.head, geomNew3.head))
      }
    }
  }

  test("Test findStartingPoints When adding (New) links before and after the existing (NotHandled) road that goes towards the digitization Then the road should still maintain the previous existing direction") {
    testNewExistingNew(LinkStatus.NotHandled, SideCode.TowardsDigitizing)
  }

  test("Test findStartingPoints When adding (New) links before and after the existing (New) road that goes towards the digitization Then the road should still maintain the previous existing direction") {
    testNewExistingNew(LinkStatus.New, SideCode.TowardsDigitizing)
  }

  test("Test findStartingPoints When adding (New) links before and after the existing (Transfer) road that goes towards the digitization Then the road should still maintain the previous existing direction") {
    testNewExistingNew(LinkStatus.Transfer, SideCode.TowardsDigitizing)
  }

  test("Test findStartingPoints When adding (New) links before and after the existing (NotHandled) road that goes against the digitization Then the road should still maintain the previous existing direction") {
    testNewExistingNew(LinkStatus.NotHandled, SideCode.AgainstDigitizing)
  }

  test("Test findStartingPoints When adding (New) links before and after the existing (New) road that goes against the digitization Then the road should still maintain the previous existing direction") {
    testNewExistingNew(LinkStatus.New, SideCode.AgainstDigitizing)
  }

  test("Test findStartingPoints When adding (New) links before and after the existing (Transfer) road that goes against the digitization Then the road should still maintain the previous existing direction") {
    testNewExistingNew(LinkStatus.Transfer, SideCode.AgainstDigitizing)
  }

  /*
        |
        |   <- #2
       / \
      |   |  <- #0 / #1
       \ /
        |   <- #3
        |
   */
  private def testNewExistingTwoTrackNew(statusOfExisting: LinkStatus, sideCode: SideCode): Unit = {
    runWithRollback {
      val geom0 = Seq(Point(10.0, 10.0), Point(5.0, 15.0), Point(10.0, 20.0))
      val geom1 = Seq(Point(10.0, 10.0), Point(15.0, 15.0), Point(10.0, 20.0))

      val projectLink0 = ProjectLink(0, 9999L, 1L, if (sideCode == TowardsDigitizing) Track.LeftSide else Track.RightSide, Discontinuity.Continuous, 0L, 10L, 0L, 0L, None, None,
        None, 12344L, 0.0, GeometryUtils.geometryLength(geom0), sideCode, (None, None),
        geom0, 0L, statusOfExisting, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom0), 0L, 0, 0, reversed = false,
        None, 86400L)

      val projectLink1 = ProjectLink(1, 9999L, 1L, if (sideCode == TowardsDigitizing) Track.RightSide else Track.LeftSide, Discontinuity.Continuous, 0L, 10L, 0L, 0L, None, None,
        None, 12344L, 0.0, GeometryUtils.geometryLength(geom1), sideCode, (None, None),
        geom1, 0L, statusOfExisting, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom1), 0L, 0, 0, reversed = false,
        None, 86400L)

      val geomNew2 = Seq(Point(10.0, 20.0), Point(10.0, 30.0))
      val geomNew3 = Seq(Point(10.0, 0.0), Point(10.0, 10.0))

      val projectLinkNew2 = ProjectLink(2, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12345L, 0.0, 0.0, SideCode.Unknown, (None, None),
        geomNew2, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew2), 0L, 0, 0, reversed = false,
        None, 86400L)

      val projectLinkNew3 = ProjectLink(3, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12346L, 0.0, 0.0, SideCode.Unknown, (None, None),
        geomNew3, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew3), 0L, 0, 0, reversed = false,
        None, 86400L)

      val otherProjectLinks = Seq(projectLink0, projectLink1)
      val newProjectLinks = Seq(projectLinkNew2, projectLinkNew3)

      val startingPointsForCalculations = defaultSectionCalculatorStrategy.findStartingPoints(newProjectLinks, otherProjectLinks, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
      if (sideCode == SideCode.AgainstDigitizing) {
        startingPointsForCalculations should be((geomNew2.last, geomNew2.last))
      } else {
        startingPointsForCalculations should be((geomNew3.head, geomNew3.head))
      }
    }
  }

  test("Test findStartingPoints When adding (New) links before and after the existing (NotHandled) two track road that goes towards the digitization Then the road should still maintain the previous existing direction") {
    testNewExistingTwoTrackNew(LinkStatus.NotHandled, SideCode.TowardsDigitizing)
  }

  test("Test findStartingPoints When adding (New) links before and after the existing (New) two track road that goes towards the digitization Then the road should still maintain the previous existing direction") {
    testNewExistingTwoTrackNew(LinkStatus.New, SideCode.TowardsDigitizing)
  }

  test("Test findStartingPoints When adding (New) links before and after the existing (Transfer) two track road that goes towards the digitization Then the road should still maintain the previous existing direction") {
    testNewExistingTwoTrackNew(LinkStatus.Transfer, SideCode.TowardsDigitizing)
  }

  test("Test findStartingPoints When adding (New) links before and after the existing (NotHandled) two track road that goes against the digitization Then the road should still maintain the previous existing direction") {
    testNewExistingTwoTrackNew(LinkStatus.NotHandled, SideCode.AgainstDigitizing)
  }

  test("Test findStartingPoints When adding (New) links before and after the existing (New) two track road that goes against the digitization Then the road should still maintain the previous existing direction") {
    testNewExistingTwoTrackNew(LinkStatus.New, SideCode.AgainstDigitizing)
  }

  test("Test findStartingPoints When adding (New) links before and after the existing (Transfer) two track road that goes against the digitization Then the road should still maintain the previous existing direction") {
    testNewExistingTwoTrackNew(LinkStatus.Transfer, SideCode.AgainstDigitizing)
  }

}

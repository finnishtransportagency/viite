package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.{GeometryUtils, Point}
import fi.liikennevirasto.digiroad2.asset.{AdministrativeClass, LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.asset.LinkGeomSource.{ComplementaryLinkInterface, FrozenLinkInterface, NormalLinkInterface}
import fi.liikennevirasto.digiroad2.asset.SideCode.TowardsDigitizing
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.CalibrationPointType
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.CalibrationPointType.{JunctionPointCP, NoCP, RoadAddressCP}
import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.process.strategy.DefaultSectionCalculatorStrategy
import fi.liikennevirasto.viite.util.CalibrationPointsUtils
import org.joda.time.DateTime
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession

class DefaultSectionCalculatorStrategySpec extends FunSuite with Matchers {
  def runWithRollback(f: => Unit): Unit = {
   Database.forDataSource(PostGISDatabase.ds).withDynTransaction {
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

    val projectLink1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 1L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom1, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom1), 0L, 0, 0, reversed = false, None, 86400L)
    val projectLink2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 2L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom2, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom2), 0L, 0, 0, reversed = false, None, 86400L)
    val projectLink3 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 3L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom3, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom3), 0L, 0, 0, reversed = false, None, 86400L)
    val projectLink4 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 4L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom4, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom4), 0L, 0, 0, reversed = false, None, 86400L)
    val projectLink5 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 5L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom5, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom5), 0L, 0, 0, reversed = false, None, 86400L)
    val projectLink6 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 6L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom6, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom6), 0L, 0, 0, reversed = false, None, 86400L)
    val projectLink7 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 7L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom7, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom7), 0L, 0, 0, reversed = false, None, 86400L)
    val projectLink8 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 8L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom8, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom8), 0L, 0, 0, reversed = false, None, 86400L)
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
      Roadway(p.roadwayId, p.roadwayNumber, p.roadNumber, p.roadPartNumber, p.administrativeClass, p.track, p.discontinuity, p.startAddrMValue, p.endAddrMValue, p.reversed, startDate, p.endDate,
        p.createdBy.getOrElse("-"), p.roadName, p.ely, TerminationCode.NoTermination, DateTime.now(), None))
  }

  test("Test defaultSectionCalculatorStrategy.assignMValues() and findStartingPoints When using 4 geometries that end up in a point " +
    "Then return the same project links, but now with correct MValues and directions") {
    runWithRollback {
      val geomLeft1 = Seq(Point(10.0, 10.0), Point(20.0, 10.0))
      val geomLeft2 = Seq(Point(20.0, 10.0), Point(30.0, 10.0))

      val projectLinkLeft1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12345L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomLeft1, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft1), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkLeft2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12346L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomLeft2, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft2), 0L, 0, 0, reversed = false, None, 86400L)

      val geomRight1 = Seq(Point(10.0, 20.0), Point(20.0, 20.0))
      val geomRight2 = Seq(Point(20.0, 20.0), Point(30.0, 20.0))

      val projectLinkRight1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12347L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomRight1, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight1), 0L, 0, 0, reversed = false, None, 86400L)

      val projectLinkRight2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12348L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomRight2, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight2), 0L, 0, 0, reversed = false, None, 86400L)

      val leftSideProjectLinks = Seq(projectLinkLeft1, projectLinkLeft2)
      val rightSideProjectLinks = Seq(projectLinkRight1, projectLinkRight2)
      val newProjectLinks = leftSideProjectLinks ++ rightSideProjectLinks

      val projectLinksWithAssignedValues = defaultSectionCalculatorStrategy.assignMValues(newProjectLinks, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
      val startingPointsForCalculations = defaultSectionCalculatorStrategy.findStartingPoints(newProjectLinks, Seq.empty[ProjectLink], Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
      projectLinksWithAssignedValues.forall(_.sideCode == projectLinksWithAssignedValues.head.sideCode) should be(true)
      startingPointsForCalculations should be((geomRight2.last, geomLeft2.last))

      val additionalGeomLeft1 = Seq(Point(40.0, 10.0), Point(30.0, 10.0))
      val additionalGeomRight1 = Seq(Point(40.0, 20.0), Point(30.0, 20.0))

      val additionalProjectLinkLeft1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12349L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), additionalGeomLeft1, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(additionalGeomLeft1), 0L, 0, 0, reversed = false, None, 86400L)

      val additionalProjectLinkRight1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12350L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), additionalGeomRight1, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(additionalGeomRight1), 0L, 0, 0, reversed = false, None, 86400L)

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

      val additionalProjectLinkLeftBefore = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12351L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), additionalGeomLeftBefore, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(additionalGeomLeftBefore), 0L, 0, 0, reversed = false, None, 86400L)

      val additionalProjectLinkRightBefore = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12352L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), additionalGeomRightBefore, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(additionalGeomRightBefore), 0L, 0, 0, reversed = false, None, 86400L)

      val leftSideBeforeProjectLinks = Seq(additionalProjectLinkLeftBefore)
      val rightSideBeforeProjectLinks = Seq(additionalProjectLinkRightBefore)
      val beforeProjectLinks = leftSideBeforeProjectLinks ++ rightSideBeforeProjectLinks

      val projectLinksWithAssignedValuesBefore = defaultSectionCalculatorStrategy.assignMValues(projectLinksWithAssignedValues ++ beforeProjectLinks, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
      projectLinksWithAssignedValuesBefore.filter(p => projectLinksWithAssignedValues.map(_.linkId).contains(p.linkId)).forall(_.sideCode == projectLinksWithAssignedValuesBefore.filter(p => projectLinksWithAssignedValues.map(_.linkId).contains(p.linkId)).head.sideCode) should be(true)
      projectLinksWithAssignedValuesBefore.map(_.sideCode.value).containsSlice(projectLinksWithAssignedValuesPlus.filter(p => additionalProjectLinks.map(_.linkId).contains(p.linkId)).map(_.sideCode).map(SideCode.switch).map(_.value))
    }
  }

  test("Test defaultSectionCalculatorStrategy.assignMValues() When using 2 tracks with huge discontinuity and not proper administrative class sections Then they will fail if the sections cannot be adjusted for two tracks due to their huge discontinuity") {
    runWithRollback {
      val geomLeft1 = Seq(Point(0.0, 0.0), Point(0.0, 30.0))
      val geomLeft2 = Seq(Point(0.0, 30.0), Point(0.0, 60.0))

      val projId = Sequences.nextViiteProjectId
      val roadwayId = Sequences.nextRoadwayId
      val linearLocationId = Sequences.nextLinearLocationId
      val projectLinkId = Sequences.nextProjectLinkId
      val project = Project(projId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val projectLinkLeft1 = ProjectLink(projectLinkId, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, Some("user"), 12345L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (CalibrationPointType.NoCP, CalibrationPointType.NoCP), geomLeft1, projId, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft1), 0L, 0, 0, reversed = false, None, 86400L, roadwayNumber = 12345L)
      val projectLinkLeft2 = ProjectLink(projectLinkId + 1, 9999L, 1L, Track.apply(2), Discontinuity.Discontinuous, 0L, 0L, 0L, 0L, None, None, Some("user"), 12346L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (CalibrationPointType.NoCP, CalibrationPointType.NoCP), geomLeft2, projId, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft2), 0L, 0, 0, reversed = false, None, 86400L, roadwayNumber = 12345L)

      val geomRight1 = Seq(Point(5.0, 0.0), Point(5.0, 5.0))
      val geomRight2 = Seq(Point(5.0, 5.0), Point(5.0, 62.0))

      val projectLinkRight1 = ProjectLink(projectLinkId + 2, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 5L, 0L, 5L, None, None, Some("user"), 12347L.toString, 0.0, 5.0, SideCode.Unknown, (RoadAddressCP, NoCP), (NoCP, NoCP), geomRight1, projId, LinkStatus.Transfer, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight1), roadwayId, linearLocationId, 0, reversed = false, None, 86400L, roadwayNumber = 12346L)

      val projectLinkRight2 = ProjectLink(projectLinkId + 3, 9999L, 1L, Track.apply(1), Discontinuity.Discontinuous, 5L, 62L, 5L, 62L, None, None, Some("user"), 12348L.toString, 0.0, 57.0, SideCode.Unknown, (NoCP, RoadAddressCP), (NoCP, NoCP), geomRight2, projId, LinkStatus.Transfer, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight2), roadwayId + 1, linearLocationId + 1, 0, reversed = false, None, 86400L, roadwayNumber = 12347L)

      val leftSideProjectLinks = Seq(projectLinkLeft1, projectLinkLeft2)
      val rightSideProjectLinks = Seq(projectLinkRight1, projectLinkRight2)
      val (linearLocation1, roadway1) = Seq(projectLinkRight1).map(toRoadwayAndLinearLocation).head
      val (linearLocation2, roadway2) = Seq(projectLinkRight2).map(toRoadwayAndLinearLocation).head

      buildTestDataForProject(Some(project), Some(Seq(roadway1, roadway2)), Some(Seq(linearLocation1, linearLocation2)), Some(leftSideProjectLinks ++ rightSideProjectLinks))

      defaultSectionCalculatorStrategy.assignMValues(leftSideProjectLinks, rightSideProjectLinks, Seq.empty[UserDefinedCalibrationPoint])
    }
  }

  test("Test defaultSectionCalculatorStrategy.assignMValues() When using 2 tracks with proper pairing administrative class sections Then they will calculate values properly") {
    runWithRollback {
      val geomLeft1 = Seq(Point(0.0, 0.0), Point(0.0, 30.0))
      val geomLeft2 = Seq(Point(0.0, 30.0), Point(0.0, 60.0))

      val projId = Sequences.nextViiteProjectId
      val roadwayId = Sequences.nextRoadwayId
      val linearLocationId = Sequences.nextLinearLocationId
      val roadwaynumber = 12346L
      val projectLinkId = Sequences.nextProjectLinkId
      val project = Project(projId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val projectLinkLeft1 = ProjectLink(projectLinkId, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, Some("user"), 12345L.toString, 0.0, 30.0, SideCode.Unknown, (NoCP, NoCP), (CalibrationPointType.NoCP, CalibrationPointType.NoCP), geomLeft1, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft1), 0L, 0, 0, reversed = false, None, 86400L, roadwayNumber = 12345L)
      val projectLinkLeft2 = ProjectLink(projectLinkId+1, 9999L, 1L, Track.apply(2), Discontinuity.Discontinuous, 0L, 0L, 0L, 0L, None, None, Some("user"), 12346L.toString, 0.0, 30.0, SideCode.Unknown, (NoCP, NoCP), (CalibrationPointType.NoCP, CalibrationPointType.NoCP), geomLeft2, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft2), 0L, 0, 0, reversed = false, None, 86400L, roadwayNumber = 12345L)

      val geomRight1 = Seq(Point(5.0, 0.0), Point(5.0, 5.0))
      val geomRight2 = Seq(Point(5.0, 5.0), Point(5.0, 62.0))

      val projectLinkRight1 = ProjectLink(projectLinkId + 2, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 5L, 0L, 5L, None, None, Some("user"), 12347L.toString, 0.0, 5.0, SideCode.Unknown, (NoCP, NoCP), (CalibrationPointType.NoCP, CalibrationPointType.NoCP), geomRight1, 0L, LinkStatus.Transfer, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight1), roadwayId, linearLocationId, 0, reversed = false, None, 86400L, roadwayNumber = roadwaynumber)
      val projectLinkRight2 = ProjectLink(projectLinkId+3, 9999L, 1L, Track.apply(1), Discontinuity.Discontinuous, 5L, 62L, 5L, 62L, None, None, Some("user"), 12348L.toString, 0.0, 57.0, SideCode.Unknown, (NoCP, NoCP), (CalibrationPointType.NoCP, CalibrationPointType.NoCP), geomRight2, 0L, LinkStatus.Transfer, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight2), roadwayId, linearLocationId+1, 0, reversed = false, None, 86400L, roadwayNumber = roadwaynumber)

      val leftSideProjectLinks = Seq(projectLinkLeft1, projectLinkLeft2)
      val rightSideProjectLinks = Seq(projectLinkRight1, projectLinkRight2)

      val (linearLocation1, roadway1) = Seq(projectLinkRight1).map(toRoadwayAndLinearLocation).head
      val (linearLocation2, roadway2) = Seq(projectLinkRight2).map(toRoadwayAndLinearLocation).head

      buildTestDataForProject(Some(project), Some(Seq(roadway1.copy(endAddrMValue = roadway2.endAddrMValue))), Some(Seq(linearLocation1, linearLocation2)), Some(leftSideProjectLinks ++ rightSideProjectLinks))

      val projectLinksWithAssignedValues = defaultSectionCalculatorStrategy.assignMValues(leftSideProjectLinks, rightSideProjectLinks, Seq.empty[UserDefinedCalibrationPoint])
      !projectLinksWithAssignedValues.exists(pl => pl.endAddrMValue == 0L) should be (true)
      val rwGroups = projectLinksWithAssignedValues.groupBy(pl => (pl.roadwayId, pl.roadwayNumber))
      rwGroups should have size 2
      rwGroups.contains((roadwayId, roadwaynumber)) should be (true)
    }
  }

  test("Test defaultSectionCalculatorStrategy.assignMValues() " +
       "When using 2 tracks with updating administrative class section of other track" +
       "Then they will calculate values properly.") {
    runWithRollback {
      val geomLeft1 = Seq(Point(0.0, 0.0), Point(0.0, 30.0))
      val geomLeft2 = Seq(Point(0.0, 30.0), Point(0.0, 60.0))

      val projId = Sequences.nextViiteProjectId
      val roadwayId = Sequences.nextRoadwayId
      val linearLocationId = Sequences.nextLinearLocationId
      val projectLinkId = Sequences.nextProjectLinkId
      val project = Project(projId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val projectLinkLeft1 = ProjectLink(projectLinkId, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, Some("user"), 12345L.toString, 0.0, 30.0, SideCode.Unknown, (NoCP, NoCP), (CalibrationPointType.NoCP, CalibrationPointType.NoCP), geomLeft1, 0L, LinkStatus.New, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft1), 0L, 0, 0, reversed = false, None, 86400L, roadwayNumber = 12345L)
      val projectLinkLeft2 = ProjectLink(projectLinkId+1, 9999L, 1L, Track.apply(2), Discontinuity.Discontinuous, 0L, 0L, 0L, 0L, None, None, Some("user"), 12346L.toString, 0.0, 30.0, SideCode.Unknown, (NoCP, NoCP), (CalibrationPointType.NoCP, CalibrationPointType.NoCP), geomLeft2, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft2), 0L, 0, 0, reversed = false, None, 86400L, roadwayNumber = 12345L)

      val geomRight1 = Seq(Point(5.0, 0.0), Point(5.0, 5.0))
      val geomRight2 = Seq(Point(5.0, 5.0), Point(5.0, 62.0))

      val projectLinkRight1 = ProjectLink(projectLinkId+2, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 5L, 0L, 5L, None, None, Some("user"), 12347L.toString, 0.0, 5.0, SideCode.Unknown, (NoCP, NoCP), (CalibrationPointType.NoCP, CalibrationPointType.NoCP), geomRight1, 0L, LinkStatus.Transfer, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight1), roadwayId, linearLocationId, 0, reversed = false, None, 86400L, roadwayNumber = 12346L)
      val projectLinkRight2 = ProjectLink(projectLinkId+3, 9999L, 1L, Track.apply(1), Discontinuity.Discontinuous, 5L, 62L, 5L, 62L, None, None, Some("user"), 12348L.toString, 0.0, 57.0, SideCode.Unknown, (NoCP, NoCP), (CalibrationPointType.NoCP, CalibrationPointType.NoCP), geomRight2, 0L, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight2), roadwayId, linearLocationId+1, 0, reversed = false, None, 86400L, roadwayNumber = 12346L)

      val leftSideProjectLinks = Seq(projectLinkLeft1, projectLinkLeft2)
      val rightSideProjectLinks = Seq(projectLinkRight1, projectLinkRight2)

      val (linearLocation1, roadway1) = Seq(projectLinkRight1).map(toRoadwayAndLinearLocation).head
      val (linearLocation2, roadway2) = Seq(projectLinkRight2).map(toRoadwayAndLinearLocation).head

      buildTestDataForProject(Some(project), Some(Seq(roadway1.copy(endAddrMValue = roadway2.endAddrMValue, administrativeClass = AdministrativeClass.State))), Some(Seq(linearLocation1, linearLocation2)), Some(leftSideProjectLinks ++ rightSideProjectLinks))

      val projectLinksWithAssignedValues = defaultSectionCalculatorStrategy.assignMValues(leftSideProjectLinks, rightSideProjectLinks, Seq.empty[UserDefinedCalibrationPoint])

      !projectLinksWithAssignedValues.exists(pl => pl.endAddrMValue == 0L) should be (true)
      val rwGroups = projectLinksWithAssignedValues.groupBy(pl => (pl.roadwayId, pl.roadwayNumber))
      rwGroups should have size 4
    }
  }

  test("Test defaultSectionCalculatorStrategy.assignMValues() " +
                 "When a two track road has a new link on the other track" +
                 "Then roadway address lengths' should be preserved.") {
    runWithRollback {
      val geomLeft1 = Seq(Point(0.0, 0.0), Point(0.0, 30.0))
      val geomLeft2 = Seq(Point(0.0, 30.0), Point(0.0, 80.0))
      val geomLeft3 = Seq(Point(0.0, 80.0), Point(0.0, 102.0))

      val projId = Sequences.nextViiteProjectId
      val roadwayId = Sequences.nextRoadwayId
      val linearLocationId = Sequences.nextLinearLocationId
      val project = Project(projId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      def nextPlId: Long = Sequences.nextProjectLinkId
      def getEndMValue(ps:  Seq[Point]): Double = ps.last.y - ps.head.y

      val projectLinkLeft1 = ProjectLink(nextPlId, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 30L, 0L, 30L, None, None, Some("user"), 12345L.toString, 0.0, getEndMValue(geomLeft1), SideCode.Unknown, (NoCP, NoCP), (CalibrationPointType.NoCP, CalibrationPointType.NoCP), geomLeft1, 0L, LinkStatus.UnChanged, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft1), roadwayId, linearLocationId, 0, reversed = false, None, 86400L, roadwayNumber = 12345L)
      val projectLinkLeft2 = ProjectLink(nextPlId, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, Some("user"), 12346L.toString, 0.0, getEndMValue(geomLeft2), SideCode.Unknown, (NoCP, NoCP), (CalibrationPointType.NoCP, CalibrationPointType.NoCP), geomLeft2, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft2), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkLeft3 = ProjectLink(nextPlId, 9999L, 1L, Track.apply(2), Discontinuity.Discontinuous, 0L, 24L, 0L, 24L, None, None, Some("user"), 12347L.toString, 0.0, getEndMValue(geomLeft3), SideCode.Unknown, (NoCP, NoCP), (CalibrationPointType.NoCP, CalibrationPointType.NoCP), geomLeft3, 0L, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft3), roadwayId+1, linearLocationId+1, 0, reversed = false, None, 86400L, roadwayNumber = 12347L)

      val geomRight1 = Seq(Point(5.0, 0.0), Point(5.0, 20.0))
      val geomRight2 = Seq(Point(5.0, 20.0), Point(5.0, 62.0))
      val geomRight3 = Seq(Point(5.0, 62.0), Point(5.0, 84.0))

      val projectLinkRight1 = ProjectLink(nextPlId, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 20L, 0L, 20L, None, None, Some("user"), 12348L.toString, 0.0, getEndMValue(geomRight1), SideCode.Unknown, (NoCP, NoCP), (CalibrationPointType.NoCP, CalibrationPointType.NoCP), geomRight1, 0L, LinkStatus.UnChanged, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight1), roadwayId+2, linearLocationId+2, 0, reversed = false, None, 86400L, roadwayNumber = 12348L)
      val projectLinkRight2 = ProjectLink(nextPlId, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 20L, 62L, 20L, 62L, None, None, Some("user"), 12349L.toString, 0.0, getEndMValue(geomRight2), SideCode.Unknown, (NoCP, NoCP), (CalibrationPointType.NoCP, CalibrationPointType.NoCP), geomRight2, 0L, LinkStatus.UnChanged, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight2), roadwayId+3, linearLocationId+3, 0, reversed = false, None, 86400L, roadwayNumber = 12349L)
      val projectLinkRight3 = ProjectLink(nextPlId, 9999L, 1L, Track.apply(1), Discontinuity.Discontinuous, 0L, 22L, 0L, 22L, None, None, Some("user"), 12350L.toString, 0.0, getEndMValue(geomRight3), SideCode.Unknown, (NoCP, NoCP), (CalibrationPointType.NoCP, CalibrationPointType.NoCP), geomRight3, 0L, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight3), roadwayId+4, linearLocationId+4, 0, reversed = false, None, 86400L, roadwayNumber = 12350L)

      val leftSideProjectLinks = Seq(projectLinkLeft1, projectLinkLeft2, projectLinkLeft3)
      val rightSideProjectLinks = Seq(projectLinkRight1, projectLinkRight2, projectLinkRight3)

      val (linearLocation1, roadway1) = Seq(projectLinkRight1).map(toRoadwayAndLinearLocation).head
      val (linearLocation2, roadway2) = Seq(projectLinkRight2).map(toRoadwayAndLinearLocation).head
      val (linearLocation3, roadway3) = Seq(projectLinkRight3).map(toRoadwayAndLinearLocation).head
      val (linearLocation4, roadway4) = Seq(projectLinkLeft1).map(toRoadwayAndLinearLocation).head
      val (linearLocation5, roadway5) = Seq(projectLinkLeft3).map(toRoadwayAndLinearLocation).head

      buildTestDataForProject(Some(project), Some(Seq(roadway1, roadway2, roadway3.copy(roadPartNumber = 2), roadway4, roadway5.copy(roadPartNumber = 2))), Some(Seq(linearLocation1, linearLocation2, linearLocation3, linearLocation4, linearLocation5)), Some(leftSideProjectLinks ++ rightSideProjectLinks))

      val projectLinksWithAssignedValues = defaultSectionCalculatorStrategy.assignMValues(Seq(projectLinkLeft2), rightSideProjectLinks ++ Seq(projectLinkLeft1, projectLinkLeft3), Seq.empty[UserDefinedCalibrationPoint])

      !projectLinksWithAssignedValues.exists(pl => pl.isNotCalculated) should be (true)
      val rwGroups = projectLinksWithAssignedValues.groupBy(pl => (pl.roadwayId, pl.roadwayNumber))
      rwGroups should have size 6
      /* Check roadway lengths of Unchanged and Transferred links are preserved. */
      val roadwayLengths = Seq(roadway1, roadway2, roadway3).map(rw => rw.id → (rw.endAddrMValue - rw.startAddrMValue)).toMap
      val calculatedLengths = projectLinksWithAssignedValues.filter(_.track == Track.RightSide).groupBy(_.roadwayId).mapValues(_.map(_.addrMLength()).sum)
      roadwayLengths.keys.foreach(k => roadwayLengths(k) should be(calculatedLengths(k)))
      val roadwayLengthsLeft = Seq(roadway4, roadway5).map(rw => rw.id → (rw.endAddrMValue - rw.startAddrMValue)).toMap
      val calculatedLengthsLeft = projectLinksWithAssignedValues.filter(_.track == Track.LeftSide).groupBy(_.roadwayId).mapValues(_.map(_.addrMLength()).sum)
      roadwayLengthsLeft.keys.foreach(k => roadwayLengthsLeft(k) should be(calculatedLengthsLeft(k)))
    }
  }

  test("Test defaultSectionCalculatorStrategy.assignMValues() When using 2 tracks (mismatching link numbers) with proper pairing administrative class sections Then they will calculate values properly") {
    runWithRollback {
      val geomLeft1 = Seq(Point(0.0, 0.0), Point(0.0, 60.0))

      val projId = Sequences.nextViiteProjectId
      val roadwayId = Sequences.nextRoadwayId
      val linearLocationId = Sequences.nextLinearLocationId
      val projectLinkId = Sequences.nextProjectLinkId
      val project = Project(projId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val projectLinkLeft1 = ProjectLink(projectLinkId, 9999L, 1L, Track.apply(2), Discontinuity.Discontinuous, 0L, 0L, 0L, 0L, None, None, Some("user"), 12345L.toString, 0.0, 60.0, SideCode.Unknown, (NoCP, NoCP), (CalibrationPointType.NoCP, CalibrationPointType.NoCP), geomLeft1, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft1), 0L, 0, 0, reversed = false, None, 86400L, roadwayNumber = 12345L)

      val geomRight1 = Seq(Point(5.0, 0.0), Point(5.0, 5.0))
      val geomRight2 = Seq(Point(5.0, 5.0), Point(5.0, 62.0))

      val projectLinkRight1 = ProjectLink(projectLinkId+2, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 5L, 0L, 5L, None, None, Some("user"), 12347L.toString, 0.0, 5.0, SideCode.Unknown, (NoCP, NoCP), (CalibrationPointType.NoCP, CalibrationPointType.NoCP), geomRight1, 0L, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight1), roadwayId, linearLocationId, 0, reversed = false, None, 86400L, roadwayNumber = 12346L)

      val projectLinkRight2 = ProjectLink(projectLinkId+3, 9999L, 1L, Track.apply(1), Discontinuity.Discontinuous, 5L, 62L, 5L, 62L, None, None, Some("user"), 12348L.toString, 0.0, 57.0, SideCode.Unknown, (NoCP, NoCP), (CalibrationPointType.NoCP, CalibrationPointType.NoCP), geomRight2, 0L, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight2), roadwayId, linearLocationId+1, 0, reversed = false, None, 86400L, roadwayNumber = 12347L)

      val leftSideProjectLinks = Seq(projectLinkLeft1)
      val rightSideProjectLinks = Seq(projectLinkRight1, projectLinkRight2)

      val (linearLocation1, roadway1) = Seq(projectLinkRight1).map(toRoadwayAndLinearLocation).head
      val (linearLocation2, roadway2) = Seq(projectLinkRight2).map(toRoadwayAndLinearLocation).head

      buildTestDataForProject(Some(project), Some(Seq(roadway1.copy(endAddrMValue = roadway2.endAddrMValue))), Some(Seq(linearLocation1, linearLocation2)), Some(leftSideProjectLinks ++ rightSideProjectLinks))

      val projectLinksWithAssignedValues = defaultSectionCalculatorStrategy.assignMValues(leftSideProjectLinks, rightSideProjectLinks, Seq.empty[UserDefinedCalibrationPoint])
      !projectLinksWithAssignedValues.exists(pl => pl.endAddrMValue == 0L) should be (true)
    }
  }

  test("Test defaultSectionCalculatorStrategy.assignMValues() " +
                 "When two track road need a split at status change " +
                 "Then there should be one split and start and end addresses equal.") {
    runWithRollback {
      val geomLeft1 = Seq(Point(640585.759, 6945368.243, 82.05899999999383), Point(640581.046, 6945375.263, 82.05999999999767), Point(640549.13, 6945426.148, 82.13800000000629), Point(640527.345, 6945456.853, 82.26099867194839))
      val geomLeft2 = Seq(Point(640647.318, 6945298.805, 81.94899999999325), Point(640631.604, 6945314.631, 81.86199999999371), Point(640619.909, 6945328.505, 81.71499999999651), Point(640603.459, 6945347.136, 82.12300000000687), Point(640592.482, 6945360.353, 82.10599999999977), Point(640585.759, 6945368.243, 82.05899999999383))

      val projId = Sequences.nextViiteProjectId
      val roadwayId = Sequences.nextRoadwayId
      val linearLocationId = Sequences.nextLinearLocationId
      val projectLinkId = Sequences.nextProjectLinkId
      val project = Project(projId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val projectLinkRight1 = ProjectLink(projectLinkId, 9999L, 1L, Track.RightSide, Discontinuity.Continuous, 0L, 117L, 0L, 117L, None, None, Some("user"), 12345L.toString, 0.0, 106.169, SideCode.Unknown, (NoCP, NoCP), (CalibrationPointType.NoCP, CalibrationPointType.NoCP), geomLeft1, projId, LinkStatus.UnChanged, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft1), 0L, 0, 0, reversed = false, None, 86400L, roadwayNumber = 12345L)
      val projectLinkRight2 = ProjectLink(projectLinkId + 1, 9999L, 1L, Track.RightSide, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, Some("user"), 12346L.toString, 0.0, 92.849, SideCode.Unknown, (NoCP, NoCP), (CalibrationPointType.NoCP, CalibrationPointType.NoCP), geomLeft2, projId, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft2), 0L, 0, 0, reversed = false, None, 86400L)

      val geomRight2 = Seq(Point(640647.318, 6945298.805, 81.94899999999325), Point(640640.688, 6945310.671, 81.96700000000419), Point(640613.581, 6945354.331, 82.08299999999872), Point(640583.638, 6945402.111, 82.11299999999756), Point(640555.489, 6945443.729, 82.16999999999825), Point(640535.923, 6945471.794, 82.31699959446674))

      val projectLinkLeft1 = ProjectLink(projectLinkId + 3, 9999L, 1L, Track.LeftSide, Discontinuity.Continuous, 0L, 228L, 0L, 228L, None, None, Some("user"), 12348L.toString, 0.0, 205.826, SideCode.Unknown, (NoCP, RoadAddressCP), (NoCP, NoCP), geomRight2, projId, LinkStatus.UnChanged, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight2), roadwayId + 1, linearLocationId + 1, 0, reversed = false, None, 86400L, roadwayNumber = 12347L)

      val leftSideProjectLinks = Seq(projectLinkLeft1)
      val rightSideProjectLinks = Seq(projectLinkRight1, projectLinkRight2)
      val (linearLocation1, roadway1) = Seq(projectLinkLeft1).map(toRoadwayAndLinearLocation).head
      val (linearLocation2, roadway2) = Seq(projectLinkRight1).map(toRoadwayAndLinearLocation).head

      buildTestDataForProject(Some(project), Some(Seq(roadway1, roadway2)), Some(Seq(linearLocation1, linearLocation2)), Some(leftSideProjectLinks ++ rightSideProjectLinks))

      val projectLinksWithAssignedValues = defaultSectionCalculatorStrategy.assignMValues(Seq(projectLinkRight2), Seq(projectLinkLeft1, projectLinkRight1), Seq.empty[UserDefinedCalibrationPoint])
      val grouped = projectLinksWithAssignedValues.groupBy(_.track)

      grouped should have size 2
      val leftCalculated  = grouped(Track.LeftSide)
      val rightCalculated = grouped(Track.RightSide)
      leftCalculated.maxBy(_.endAddrMValue).endAddrMValue should be(rightCalculated.maxBy(_.endAddrMValue).endAddrMValue)
      leftCalculated.minBy(_.startAddrMValue).startAddrMValue should be(rightCalculated.minBy(_.startAddrMValue).startAddrMValue)
      leftCalculated should have size 2
      rightCalculated should have size 2
    }
  }

  test("Test defaultSectionCalculatorStrategy.assignMValues() " +
                 "When a two track road has a 90 degree turn (i.e. 'hashtag turn' #) having minor discontinuity on right track " +
                 "Then address calculation should be successfully. No changes to tracks or addresses are expected.") {
    runWithRollback {
      // A simplified version of road 1 part 2 to address # turn challenge.
      /*
       ^      ^
       | L    |R4
       |      |       R1
       |<- - -|<- - - -
       ^  R2  ^
       |L     |R3     L
       |<- - -|<- - - -
           L

         Note:
         R*: Right track
         R2: Minor discontinuity on Right track
         L: Continuous Left track
      */

      val project_id     = Sequences.nextViiteProjectId
      val roadNumber     = 1
      val roadPartNumber = 2
      val createdBy      = "test"
      val roadName       = None

      val geomRight1       = List(Point(384292.0, 6674530.0), Point(384270.0, 6674532.0), Point(382891.0, 6675103.0))
      val geomRight1length = GeometryUtils.geometryLength(geomRight1)
      val geomRight2       = List(Point(382737.0, 6675175.0), Point(382737.0, 6675213.0), Point(382569.0, 6675961.0))
      val geomRight2length = GeometryUtils.geometryLength(geomRight2)
      val geomLeft3        = List(Point(384288.0, 6674517.0), Point(384218.0, 6674520.0), Point(382778.0, 6675136.0))
      val geomLeft3length  = GeometryUtils.geometryLength(geomLeft3)
      val geomLeft4        = List(Point(382714.0, 6675186.0), Point(382720.0, 6675247.0), Point(382581.0, 6675846.0))
      val geomLeft4length  = GeometryUtils.geometryLength(geomLeft4)

      val projectLinks = Seq(
        ProjectLink(1000,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,0,1550,0,1550,None,None,Some(createdBy),452570.toString,0.0,geomRight1length,SideCode.TowardsDigitizing,(RoadAddressCP,JunctionPointCP),(RoadAddressCP,JunctionPointCP),geomRight1,project_id,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,geomRight1length,761,4558,1,false,None,1629932416000L,38259,roadName,None,None,None,None,None,None),
        ProjectLink(1037,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,1550,1717,1550,1717,None,None,Some(createdBy),451986.toString,0.0,170.075,SideCode.TowardsDigitizing,(NoCP,JunctionPointCP),(NoCP,JunctionPointCP),List(Point(382891.0,6675103.0,0.0), Point(382737.0,6675175.0,0.0)),project_id,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,170.075,761,4576,1,false,None,1629932416000L,38259,roadName,None,None,None,None,None,None),
        ProjectLink(1040,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.MinorDiscontinuity,1717,1742,1717,1742,None,None,Some(createdBy),452008.toString,0.0,24.988,SideCode.TowardsDigitizing,(JunctionPointCP,RoadAddressCP),(JunctionPointCP,RoadAddressCP),List(Point(382737.0,6675175.0,0.0), Point(382714.0,6675186.0,0.0)),project_id,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,24.988,761,4577,1,false,None,1629932416000L,38259,roadName,None,None,None,None,None,None),
        ProjectLink(1042,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,1742,1760,1742,1760,None,None,Some(createdBy),452012.toString,0.0,19.26,SideCode.TowardsDigitizing,(RoadAddressCP,JunctionPointCP),(RoadAddressCP,JunctionPointCP),List(Point(382728.0,6675158.0,0.0), Point(382737.0,6675175.0,0.0)),project_id,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,19.26,80546,458872,1,false,None,1629932416000L,202219273,roadName,None,None,None,None,None,None),
        ProjectLink(1044,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,1760,2547,1760,2547,None,None,Some(createdBy),452007.toString,0.0,geomRight2length,SideCode.TowardsDigitizing,(JunctionPointCP,NoCP),(JunctionPointCP,NoCP),geomRight2,project_id,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,geomRight2length,80546,458873,1,false,None,1629932416000L,202219273,roadName,None,None,None,None,None,None),
        ProjectLink(1060,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,2547,2570,2547,2570,None,None,Some(createdBy),450651.toString,0.0,23.353,SideCode.TowardsDigitizing,(JunctionPointCP,RoadAddressCP),(JunctionPointCP,RoadAddressCP),List(Point(382569.0,6675961.0,0.0), Point(382555.0,6675978.0,0.0)),project_id,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,23.353,80546,458880,1,false,None,1629932416000L,202219273,roadName,None,None,None,None,None,None),

        ProjectLink(1001,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,0,1673,0,1673,None,None,Some(createdBy),452566.toString,0.0,geomLeft3length,SideCode.TowardsDigitizing,(RoadAddressCP,NoCP),(RoadAddressCP,NoCP),geomLeft3,project_id,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,geomLeft3length ,715,4288,1,false,None,1629932416000L,38260,roadName,None,None,None,None,None,None),
        ProjectLink(1038,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,1673,1726,1673,1726,None,None,Some(createdBy),452005.toString,0.0,54.292,SideCode.TowardsDigitizing,(NoCP,JunctionPointCP),(NoCP,JunctionPointCP),List(Point(382778.0,6675136.0,0.0), Point(382728.0,6675158.0,0.0)),project_id,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,54.292,715,4307,1,false,None,1629932416000L,38260,roadName,None,None,None,None,None,None),
        ProjectLink(1039,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,1726,1742,1726,1742,None,None,Some(createdBy),452014.toString,0.0,16.468,SideCode.TowardsDigitizing,(JunctionPointCP,NoCP),(JunctionPointCP,NoCP),List(Point(382728.0,6675158.0,0.0), Point(382713.0,6675164.0,0.0)),project_id,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,16.468,715,4308,1,false,None,1629932416000L,38260,roadName,None,None,None,None,None,None),
        ProjectLink(1041,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,1742,1752,1742,1752,None,None,Some(createdBy),452014.toString,16.468,26.761,SideCode.TowardsDigitizing,(NoCP,JunctionPointCP),(NoCP,JunctionPointCP),List(Point(382713.0,6675164.0,0.0), Point(382704.0,6675168.0,0.0)),project_id,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,10.293,80486,458576,1,false,None,1629932416000L,202219282,roadName,None,None,None,None,None,None),
        ProjectLink(1043,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,1752,1773,1752,1773,None,None,Some(createdBy),451992.toString,0.0,21.002,SideCode.TowardsDigitizing,(JunctionPointCP,JunctionPointCP),(JunctionPointCP,JunctionPointCP),List(Point(382704.0,6675168.0,0.0), Point(382714.0,6675186.0,0.0)),project_id,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,21.002,80486,458577,1,false,None,1629932416000L,202219282,roadName,None,None,None,None,None,None),
        ProjectLink(1045,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,1773,2438,1773,2438,None,None,Some(createdBy),452002.toString,0.0,geomLeft4length,SideCode.TowardsDigitizing,(JunctionPointCP,NoCP),(JunctionPointCP,NoCP),geomLeft4,project_id,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,geomLeft4length,80486,458578,1,false,None,1629932416000L,202219282,roadName,None,None,None,None,None,None),
        ProjectLink(1059,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Discontinuous,2438,2570,2438,2570,None,None,Some(createdBy),450646.toString,0.0,134.664,SideCode.TowardsDigitizing,(JunctionPointCP,RoadAddressCP),(JunctionPointCP,RoadAddressCP),List(Point(382581.0,6675846.0,0.0), Point(382555.0,6675978.0,0.0)),project_id,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,134.664,80486,458586,1,false,None,1629932416000L,202219282,roadName,None,None,None,None,None,None)
      )

      val roadways = Seq(
        Roadway(715,38260,1,2,AdministrativeClass.Municipality,Track.LeftSide,Discontinuity.Continuous,0,1742,reversed = false,DateTime.parse("2018-07-01T00:00:00.000+03:00"),None,createdBy,roadName,1,TerminationCode.NoTermination,DateTime.parse("2018-07-05T00:00:00.000+03:00"),None),
        Roadway(761,38259,1,2,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.MinorDiscontinuity,0,1742,reversed = false,DateTime.parse("2018-07-01T00:00:00.000+03:00"),None,createdBy,roadName,1,TerminationCode.NoTermination,DateTime.parse("2018-07-05T00:00:00.000+03:00"),None),
        Roadway(80486,202219282,1,2,AdministrativeClass.Municipality,Track.LeftSide,Discontinuity.Discontinuous,1742,2570,reversed = false,DateTime.parse("2018-07-01T00:00:00.000+03:00"),None,createdBy,roadName,1,TerminationCode.NoTermination,DateTime.parse("2018-07-11T00:00:00.000+03:00"),None),
        Roadway(80546,202219273,1,2,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Continuous,1742,2570,reversed = false,DateTime.parse("1989-01-01T00:00:00.000+02:00"),None,createdBy,roadName,1,TerminationCode.NoTermination,DateTime.parse("2018-07-04T00:00:00.000+03:00"),None)
      )

      roadwayDAO.create(roadways)

      val calculated = defaultSectionCalculatorStrategy.assignMValues(Seq(), projectLinks, Seq.empty[UserDefinedCalibrationPoint])

      calculated.foreach(pl => {
        pl.originalTrack           should be(pl.track)
        pl.originalStartAddrMValue should be(pl.startAddrMValue)
        pl.originalEndAddrMValue   should be(pl.endAddrMValue)
      })

      calculated.filter(_.startAddrMValue == 0)    should have size 2
      calculated.filter(_.endAddrMValue   == 2570) should have size 2

    }
  }

  test("Test defaultSectionCalculatorStrategy.assignMValues() " +
       "When combined + two track road having roundabout added on two track part with termination " +
       "Then address calculation should be successfully.") {
    // VIITE-2814
    runWithRollback {
      val roadNumber = 3821
      val roadPartNumber = 2
      val createdBy = "Test"
      val roadName = None
      val projectId = Sequences.nextViiteProjectId
      val project = Project(projectId, ProjectState.Incomplete, "targetAddresses", "s", DateTime.now(), "", DateTime.now(), DateTime.now(), "", Seq(), Seq(), None, None)

      val projectLinks = Seq(
        ProjectLink(1000,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,0,107,0,107,None,None,Some(createdBy),2621083.toString,0.0,105.305,SideCode.TowardsDigitizing,(RoadAddressCP,NoCP),(RoadAddressCP,NoCP),List(Point(564071.0,6769520.0,0.0), Point(564172.0,6769549.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,105.305,45508,756718,3,reversed = false,None,1533863903000L,79190,roadName,None,None,None,None,None,None),
        ProjectLink(1001,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,0,109,0,109,None,None,Some(createdBy),2621084.toString,0.0,104.894,SideCode.TowardsDigitizing,(RoadAddressCP,NoCP),(RoadAddressCP,NoCP),List(Point(564063.0,6769533.0,0.0), Point(564164.0,6769562.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,104.894,73607,743246,3,reversed = false,None,1533863903000L,148127711,roadName,None,None,None,None,None,None),
        ProjectLink(1002,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,107,132,107,132,None,None,Some(createdBy),2621089.toString,0.0,24.875,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(564172.0,6769549.0,0.0), Point(564196.0,6769557.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,24.875,45508,756711,3,reversed = false,None,1533863903000L,79190,roadName,None,None,None,None,None,None),
        ProjectLink(1003,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,109,135,109,135,None,None,Some(createdBy),2621085.toString,0.0,25.05,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(564164.0,6769562.0,0.0), Point(564188.0,6769570.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,25.05,73607,743250,3,reversed = false,None,1533863903000L,148127711,roadName,None,None,None,None,None,None),
        ProjectLink(1004,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,132,210,132,210,None,None,Some(createdBy),2621306.toString,0.0,76.535,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(564196.0,6769557.0,0.0), Point(564269.0,6769579.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,76.535,45508,756714,3,reversed = false,None,1533863903000L,79190,roadName,None,None,None,None,None,None),
        ProjectLink(1006,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,135,230,135,230,None,None,Some(createdBy),2621305.toString,0.0,91.83,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(564188.0,6769570.0,0.0), Point(564275.0,6769597.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,91.83,73607,743248,3,reversed = false,None,1533863903000L,148127711,roadName,None,None,None,None,None,None),
        ProjectLink(1005,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,210,221,210,221,None,None,Some(createdBy),2621315.toString,0.0,11.151,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(564269.0,6769579.0,0.0), Point(564280.0,6769583.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,11.151,45508,756716,3,reversed = false,None,1533863903000L,79190,roadName,None,None,None,None,None,None),
        ProjectLink(1007,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,221,236,221,236,None,None,Some(createdBy),2621318.toString,0.0,14.83,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(564280.0,6769583.0,0.0), Point(564294.0,6769587.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,14.83,45508,756710,3,reversed = false,None,1533863903000L,79190,roadName,None,None,None,None,None,None),
        ProjectLink(1010,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,230,300,230,300,None,None,Some(createdBy),2621314.toString,0.0,66.909,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(564275.0,6769597.0,0.0), Point(564339.0,6769617.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,66.909,73607,743244,3,reversed = false,None,1533863903000L,148127711,roadName,None,None,None,None,None,None),
        ProjectLink(1008,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,236,288,236,288,None,None,Some(createdBy),2621311.toString,0.0,50.696,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(564294.0,6769587.0,0.0), Point(564342.0,6769603.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,50.696,45508,756720,3,reversed = false,None,1533863903000L,79190,roadName,None,None,None,None,None,None),
        ProjectLink(1009,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,288,296,288,296,None,None,Some(createdBy),2621320.toString,0.0,8.601,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(564342.0,6769603.0,0.0), Point(564351.0,6769605.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,8.601,45508,756715,3,reversed = false,None,1533863903000L,79190,roadName,None,None,None,None,None,None),
        ProjectLink(1012,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,296,361,296,361,None,None,Some(createdBy),2621337.toString,0.0,63.921,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(564351.0,6769605.0,0.0), Point(564411.0,6769625.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,63.921,45508,756712,3,reversed = false,None,1533863903000L,79190,roadName,None,None,None,None,None,None),
        ProjectLink(1011,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,300,305,300,305,None,None,Some(createdBy),2621309.toString,0.0,4.571,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(564339.0,6769617.0,0.0), Point(564343.0,6769619.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,4.571,73607,743249,3,reversed = false,None,1533863903000L,148127711,roadName,None,None,None,None,None,None),
        ProjectLink(1013,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,305,375,305,375,None,None,Some(createdBy),2621326.toString,0.0,67.642,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(564343.0,6769619.0,0.0), Point(564408.0,6769639.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,67.642,73607,743247,3,reversed = false,None,1533863903000L,148127711,roadName,None,None,None,None,None,None),
        ProjectLink(1014,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,361,375,361,375,None,None,Some(createdBy),2621328.toString,0.0,13.674,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(564411.0,6769625.0,0.0), Point(564424.0,6769629.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,13.674,45508,756717,3,reversed = false,None,1533863903000L,79190,roadName,None,None,None,None,None,None),
        ProjectLink(1016,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,375,465,375,465,None,None,Some(createdBy),2621322.toString,0.0,88.702,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(564424.0,6769629.0,0.0), Point(564508.0,6769659.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,88.702,45508,756713,3,reversed = false,None,1533863903000L,79190,roadName,None,None,None,None,None,None),
        ProjectLink(1015,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,375,387,375,387,None,None,Some(createdBy),2621329.toString,0.0,11.818,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(564408.0,6769639.0,0.0), Point(564419.0,6769643.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,11.818,73607,743245,3,reversed = false,None,1533863903000L,148127711,roadName,None,None,None,None,None,None),
        ProjectLink(1018,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,387,483,387,483,None,None,Some(createdBy),2621323.toString,0.0,92.672,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(564419.0,6769643.0,0.0), Point(564506.0,6769674.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,92.672,73607,743242,3,reversed = false,None,1533863903000L,148127711,roadName,None,None,None,None,None,None),
        ProjectLink(1017,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,465,476,465,476,None,None,Some(createdBy),2621332.toString,0.0,10.767,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(564508.0,6769659.0,0.0), Point(564516.0,6769665.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,10.767,45508,756719,3,reversed = false,None,1533863903000L,79190,roadName,None,None,None,None,None,None),
        ProjectLink(1019,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,476,507,476,507,None,None,Some(createdBy),2621175.toString,0.0,30.585,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(564516.0,6769665.0,0.0), Point(564534.0,6769690.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,30.585,45508,756721,3,reversed = false,None,1533863903000L,79190,roadName,None,None,None,None,None,None),
        ProjectLink(1020,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,483,507,483,507,None,None,Some(createdBy),2621176.toString,0.0,22.565,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(564506.0,6769674.0,0.0), Point(564522.0,6769690.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,22.565,73607,563344,3,reversed = false,None,1533863903000L,148127711,roadName,None,None,None,None,None,None),
        ProjectLink(1022,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.MinorDiscontinuity,507,561,507,561,None,None,Some(createdBy),2621175.toString,30.585,83.861,SideCode.TowardsDigitizing,(NoCP,RoadAddressCP),(NoCP,RoadAddressCP),List(Point(564534.0,6769690.0,0.0), Point(564547.0,6769741.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,53.276,45590,351769,3,reversed = false,None,1533863903000L,79184,roadName,None,None,None,None,None,None),
        ProjectLink(1021,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.MinorDiscontinuity,507,561,507,561,None,None,Some(createdBy),2621176.toString,22.565,75.545,SideCode.TowardsDigitizing,(NoCP,RoadAddressCP),(NoCP,RoadAddressCP),List(Point(564522.0,6769690.0,0.0), Point(564537.0,6769739.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,52.98,72749,563342,3,reversed = false,None,1533863903000L,148127709,roadName,None,None,None,None,None,None),
        ProjectLink(1024,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,561,572,561,572,None,None,Some(createdBy),2621196.toString,0.0,10.965,SideCode.TowardsDigitizing,(RoadAddressCP,NoCP),(RoadAddressCP,NoCP),List(Point(564547.0,6769759.0,0.0), Point(564547.0,6769770.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,10.965,80988,754640,3,reversed = false,None,1634598047000L,202230183,roadName,None,None,None,None,None,None),
        ProjectLink(1023,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,561,572,561,572,None,None,Some(createdBy),2621199.toString,0.0,10.459,SideCode.TowardsDigitizing,(RoadAddressCP,NoCP),(RoadAddressCP,NoCP),List(Point(564537.0,6769760.0,0.0), Point(564539.0,6769770.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,10.459,81119,462093,3,reversed = false,None,1533863903000L,202230176,roadName,None,None,None,None,None,None),
        ProjectLink(1026,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,572,666,572,666,None,None,Some(createdBy),2621223.toString,0.0,90.539,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(564547.0,6769770.0,0.0), Point(564553.0,6769860.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,90.539,80988,754641,3,reversed = false,None,1634598047000L,202230183,roadName,None,None,None,None,None,None),
        ProjectLink(1025,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,572,622,572,622,None,None,Some(createdBy),2621184.toString,0.0,48.764,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(564539.0,6769770.0,0.0), Point(564542.0,6769819.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,48.764,81119,462094,3,reversed = false,None,1533863903000L,202230176,roadName,None,None,None,None,None,None),
        ProjectLink(1028,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,622,794,622,794,None,None,Some(createdBy),2621210.toString,0.0,167.07,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(564542.0,6769819.0,0.0), Point(564554.0,6769986.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,167.07,81119,462095,3,reversed = false,None,1533863903000L,202230176,roadName,None,None,None,None,None,None),
        ProjectLink(1027,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,666,776,666,776,None,None,Some(createdBy),2620906.toString,0.0,106.701,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(564553.0,6769860.0,0.0), Point(564561.0,6769966.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,106.701,80988,754639,3,reversed = false,None,1533863903000L,202230183,roadName,None,None,None,None,None,None),
        ProjectLink(1029,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,776,796,776,796,None,None,Some(createdBy),2620909.toString,0.0,19.337,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(564561.0,6769966.0,0.0), Point(564563.0,6769986.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,19.337,80988,754642,3,reversed = false,None,1533863903000L,202230183,roadName,None,None,None,None,None,None),
        ProjectLink(1031,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.MinorDiscontinuity,794,812,794,812,None,None,Some(createdBy),2620912.toString,0.0,17.014,SideCode.TowardsDigitizing,(NoCP,RoadAddressCP),(NoCP,RoadAddressCP),List(Point(564554.0,6769986.0,0.0), Point(564552.0,6770002.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,17.014,81119,462096,3,reversed = false,None,1533863903000L,202230176,roadName,None,None,None,None,None,None),
        ProjectLink(1030,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.MinorDiscontinuity,796,812,796,812,None,None,Some(createdBy),2620911.toString,0.0,15.23,SideCode.TowardsDigitizing,(NoCP,RoadAddressCP),(NoCP,RoadAddressCP),List(Point(564563.0,6769986.0,0.0), Point(564564.0,6770001.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,15.23,80988,754638,3,reversed = false,None,1533863903000L,202230183,roadName,None,None,None,None,None,None),
        ProjectLink(1033,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,812,831,812,831,None,None,Some(createdBy),2620897.toString,0.0,18.744,SideCode.TowardsDigitizing,(RoadAddressCP,NoCP),(RoadAddressCP,NoCP),List(Point(564586.0,6770020.0,0.0), Point(564603.0,6770027.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,18.744,48822,763365,3,reversed = false,None,1634598047000L,6651364,roadName,None,None,None,None,None,None),
        ProjectLink(1032,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,812,830,812,830,None,None,Some(createdBy),2620896.toString,0.0,17.476,SideCode.TowardsDigitizing,(RoadAddressCP,NoCP),(RoadAddressCP,NoCP),List(Point(564584.0,6770035.0,0.0), Point(564601.0,6770039.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,17.476,72754,743231,3,reversed = false,None,1634598047000L,148127686,roadName,None,None,None,None,None,None),
        ProjectLink(1034,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,830,1084,830,1084,None,None,Some(createdBy),2620884.toString,0.0,249.621,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(564601.0,6770039.0,0.0), Point(564844.0,6770097.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,249.621,72754,743234,3,reversed = false,None,1634598047000L,148127686,roadName,None,None,None,None,None,None),
        ProjectLink(1035,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,831,1086,831,1086,None,None,Some(createdBy),2620883.toString,0.0,249.906,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(564603.0,6770027.0,0.0), Point(564846.0,6770086.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,249.906,48822,763361,3,reversed = false,None,1533863903000L,6651364,roadName,None,None,None,None,None,None),
        ProjectLink(1036,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,1084,1242,1084,1242,None,None,Some(createdBy),2620784.toString,0.0,155.167,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(564844.0,6770097.0,0.0), Point(564995.0,6770133.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,155.167,72754,743230,3,reversed = false,None,1533863903000L,148127686,roadName,None,None,None,None,None,None),
        ProjectLink(1037,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,1086,1244,1086,1244,None,None,Some(createdBy),2620785.toString,0.0,155.679,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(564846.0,6770086.0,0.0), Point(564997.0,6770122.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,155.679,48822,763362,3,reversed = false,None,1533863903000L,6651364,roadName,None,None,None,None,None,None),
        ProjectLink(1038,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,1242,1248,1242,1248,None,None,Some(createdBy),2620798.toString,0.0,5.737,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(564995.0,6770133.0,0.0), Point(565000.0,6770134.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,5.737,72754,743233,3,reversed = false,None,1533863903000L,148127686,roadName,None,None,None,None,None,None),
        ProjectLink(1039,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,1244,1251,1244,1251,None,None,Some(createdBy),2620802.toString,0.0,6.55,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(564997.0,6770122.0,0.0), Point(565004.0,6770123.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,6.55,48822,763363,3,reversed = false,None,1533863903000L,6651364,roadName,None,None,None,None,None,None),
        ProjectLink(1040,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,1248,1590,1248,1590,None,None,Some(createdBy),2620726.toString,0.0,335.841,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(565000.0,6770134.0,0.0), Point(565327.0,6770212.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,335.841,72754,743232,3,reversed = false,None,1533863903000L,148127686,roadName,None,None,None,None,None,None),
        ProjectLink(1041,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,1251,1592,1251,1592,None,None,Some(createdBy),2620729.toString,0.0,335.083,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(565004.0,6770123.0,0.0), Point(565329.0,6770203.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,335.083,48822,763364,3,reversed = false,None,1533863903000L,6651364,roadName,None,None,None,None,None,None),
        ProjectLink(1043,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,1590,1656,1590,1656,None,None,Some(createdBy),10648023.toString,0.0,64.061,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(565327.0,6770212.0,0.0), Point(565390.0,6770226.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,64.061,72754,563330,3,reversed = false,None,1533863903000L,148127686,roadName,None,None,None,None,None,None),
        ProjectLink(1042,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,1592,1656,1592,1656,None,None,Some(createdBy),2620635.toString,0.0,63.029,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(565329.0,6770203.0,0.0), Point(565391.0,6770216.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,63.029,48822,583467,3,reversed = false,None,1533863903000L,6651364,roadName,None,None,None,None,None,None),
        ProjectLink(1045,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,1656,1829,1656,1829,None,None,Some(createdBy),2620635.toString,63.029,233.406,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(565391.0,6770216.0,0.0), Point(565559.0,6770239.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,170.377,72976,562493,3,reversed = false,None,1533863903000L,148125309,roadName,None,None,None,None,None,None),
        ProjectLink(1044,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,1656,1829,1656,1829,None,None,Some(createdBy),10648023.toString,64.061,233.576,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(565390.0,6770226.0,0.0), Point(565557.0,6770251.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,169.515,74788,564163,3,reversed = false,None,1533863903000L,148128101,roadName,None,None,None,None,None,None),
        ProjectLink(1046,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,1829,1854,1829,1854,None,None,Some(createdBy),2620697.toString,0.0,24.014,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(565559.0,6770239.0,0.0), Point(565583.0,6770242.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,24.014,72976,742393,3,reversed = false,None,1533863903000L,148125309,roadName,None,None,None,None,None,None),
        ProjectLink(1047,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,1829,1857,1829,1857,None,None,Some(createdBy),2620700.toString,0.0,27.864,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(565557.0,6770251.0,0.0), Point(565585.0,6770255.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,27.864,74788,744060,3,reversed = false,None,1533863903000L,148128101,roadName,None,None,None,None,None,None),
        ProjectLink(1049,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,1854,2098,1854,2098,None,None,Some(createdBy),2620680.toString,0.0,239.642,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(565583.0,6770242.0,0.0), Point(565820.0,6770277.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,239.642,72976,742394,3,reversed = false,None,1634598047000L,148125309,roadName,None,None,None,None,None,None),
        ProjectLink(1048,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,1857,2098,1857,2098,None,None,Some(createdBy),2620681.toString,0.0,236.094,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(565585.0,6770255.0,0.0), Point(565819.0,6770286.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,236.094,74788,744061,3,reversed = false,None,1634598047000L,148128101,roadName,None,None,None,None,None,None),
        ProjectLink(1050,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,2098,2190,2098,2190,None,None,Some(createdBy),2618679.toString,0.0,91.243,SideCode.TowardsDigitizing,(NoCP,RoadAddressCP),(NoCP,RoadAddressCP),List(Point(565820.0,6770277.0,0.0), Point(565908.0,6770303.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,91.243,72976,742395,3,reversed = false,None,1634598047000L,148125309,roadName,None,None,None,None,None,None),
        ProjectLink(1051,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,2098,2190,2098,2190,None,None,Some(createdBy),2618678.toString,0.0,90.718,SideCode.TowardsDigitizing,(NoCP,RoadAddressCP),(NoCP,RoadAddressCP),List(Point(565819.0,6770286.0,0.0), Point(565908.0,6770303.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,90.718,74788,744063,3,reversed = false,None,1634598047000L,148128101,roadName,None,None,None,None,None,None),
        ProjectLink(1052,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,2190,2501,2190,2501,None,None,Some(createdBy),2618671.toString,0.0,309.535,SideCode.TowardsDigitizing,(RoadAddressCP,NoCP),(RoadAddressCP,NoCP),List(Point(565908.0,6770303.0,0.0), Point(566205.0,6770388.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,309.535,72949,562439,3,reversed = false,None,1630018852000L,148125051,roadName,None,None,None,None,None,None),
        ProjectLink(1053,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,2501,2637,2501,2637,None,None,Some(createdBy),2618703.toString,0.0,135.096,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(566205.0,6770388.0,0.0), Point(566332.0,6770434.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,135.096,72949,562441,3,reversed = false,None,1630018852000L,148125051,roadName,None,None,None,None,None,None),
        ProjectLink(1054,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,2637,2656,2637,2656,None,None,Some(createdBy),2618716.toString,0.0,19.208,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(566332.0,6770434.0,0.0), Point(566351.0,6770439.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,19.208,72949,562438,3,reversed = false,None,1630018852000L,148125051,roadName,None,None,None,None,None,None),
        ProjectLink(1055,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,2656,2790,2656,2790,None,None,Some(createdBy),2618480.toString,0.0,133.577,SideCode.AgainstDigitizing,(NoCP,RoadAddressCP),(NoCP,RoadAddressCP),List(Point(566482.0,6770432.0,0.0), Point(566351.0,6770439.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,133.577,72949,562440,3,reversed = false,None,1630018852000L,148125051,roadName,None,None,None,None,None,None),
        ProjectLink(1056,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.MinorDiscontinuity,2790,2881,2790,2881,None,None,Some(createdBy),2618583.toString,0.0,91.162,SideCode.AgainstDigitizing,(RoadAddressCP,NoCP),(RoadAddressCP,NoCP),List(Point(566561.0,6770386.0,0.0), Point(566482.0,6770432.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,91.162,73430,574704,3,reversed = false,None,1630018852000L,148124318,roadName,None,None,None,None,None,None),
        ProjectLink(1057,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.MinorDiscontinuity,2790,2883,2790,2883,None,None,Some(createdBy),2618584.toString,0.0,93.474,SideCode.AgainstDigitizing,(RoadAddressCP,NoCP),(RoadAddressCP,NoCP),List(Point(566567.0,6770395.0,0.0), Point(566482.0,6770432.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,NormalLinkInterface,93.474,74754,564156,3,reversed = false,None,1630018852000L,148128092,roadName,None,None,None,None,None,None),
        ProjectLink(1059,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,2881,2917,2881,2917,None,None,Some(createdBy),12675142.toString,0.0,35.227,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(566591.0,6770368.0,0.0), Point(566561.0,6770386.0,0.0)),projectId,LinkStatus.Terminated,AdministrativeClass.Municipality,ComplementaryLinkInterface,35.227,73430,574702,3,reversed = false,None,1652734800000L,148124318,roadName,None,None,None,None,None,None),
        ProjectLink(1058,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,2883,2917,2883,2917,None,None,Some(createdBy),12675141.toString,0.0,34.254,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(566595.0,6770376.0,0.0), Point(566567.0,6770395.0,0.0)),projectId,LinkStatus.Terminated,AdministrativeClass.Municipality,ComplementaryLinkInterface,34.254,74754,564160,3,reversed = false,None,1652734800000L,148128092,roadName,None,None,None,None,None,None),
        ProjectLink(1061,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,2917,2946,2917,2946,None,None,Some(createdBy),2618600.toString,0.0,28.861,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(566617.0,6770354.0,0.0), Point(566591.0,6770368.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,28.861,73430,574705,3,reversed = false,None,1630018852000L,148124318,roadName,None,None,None,None,None,None),
        ProjectLink(1060,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,2917,2945,2917,2945,None,None,Some(createdBy),2618589.toString,0.0,28.254,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(566620.0,6770362.0,0.0), Point(566595.0,6770376.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,28.254,74754,564157,3,reversed = false,None,1630018852000L,148128092,roadName,None,None,None,None,None,None),
        ProjectLink(1062,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,2945,2957,2945,2957,None,None,Some(createdBy),2618605.toString,0.0,12.173,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(566631.0,6770356.0,0.0), Point(566620.0,6770362.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,12.173,74754,564159,3,reversed = false,None,1630018852000L,148128092,roadName,None,None,None,None,None,None),
        ProjectLink(1063,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,2946,2961,2946,2961,None,None,Some(createdBy),2618604.toString,0.0,14.931,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(566630.0,6770347.0,0.0), Point(566617.0,6770354.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,14.931,73430,574701,3,reversed = false,None,1630018852000L,148124318,roadName,None,None,None,None,None,None),
        ProjectLink(1064,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,2957,3020,2957,3020,None,None,Some(createdBy),2618597.toString,0.0,63.045,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,RoadAddressCP),List(Point(566683.0,6770321.0,0.0), Point(566631.0,6770356.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,63.045,74754,564158,3,reversed = false,None,1630018852000L,148128092,roadName,None,None,None,None,None,None),
        ProjectLink(1065,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,2961,3020,2961,3020,None,None,Some(createdBy),2618596.toString,0.0,59.188,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,RoadAddressCP),List(Point(566683.0,6770321.0,0.0), Point(566630.0,6770347.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,59.188,73430,574703,3,reversed = false,None,1630018852000L,148124318,roadName,None,None,None,None,None,None),
        ProjectLink(1066,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,3020,3046,3020,3046,None,None,Some(createdBy),2618610.toString,0.0,25.827,SideCode.AgainstDigitizing,(NoCP,NoCP),(RoadAddressCP,NoCP),List(Point(566705.0,6770308.0,0.0), Point(566683.0,6770321.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,25.827,73303,754554,3,reversed = false,None,1630018852000L,148122120,roadName,None,None,None,None,None,None),
        ProjectLink(1067,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,3046,3376,3046,3376,None,None,Some(createdBy),2618571.toString,0.0,327.094,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(566995.0,6770160.0,0.0), Point(566705.0,6770308.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,327.094,73303,754555,3,reversed = false,None,1630018852000L,148122120,roadName,None,None,None,None,None,None),
        ProjectLink(1068,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,3376,4136,3376,4136,None,None,Some(createdBy),2618435.toString,0.0,754.015,SideCode.TowardsDigitizing,(NoCP,JunctionPointCP),(NoCP,JunctionPointCP),List(Point(566995.0,6770160.0,0.0), Point(567657.0,6770430.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,754.015,73303,754556,3,reversed = false,None,1630018852000L,148122120,roadName,None,None,None,None,None,None),
        ProjectLink(1069,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,4136,4243,4136,4243,None,None,Some(createdBy),2618392.toString,0.0,105.993,SideCode.TowardsDigitizing,(JunctionPointCP,NoCP),(JunctionPointCP,NoCP),List(Point(567657.0,6770430.0,0.0), Point(567706.0,6770524.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,105.993,73303,754557,3,reversed = false,None,1630018852000L,148122120,roadName,None,None,None,None,None,None),
        ProjectLink(1070,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,4243,4262,4243,4262,None,None,Some(createdBy),2618315.toString,0.0,18.446,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(567706.0,6770524.0,0.0), Point(567713.0,6770541.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,18.446,73303,754559,3,reversed = false,None,1630018852000L,148122120,roadName,None,None,None,None,None,None),
        ProjectLink(1071,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,4262,4375,4262,4375,None,None,Some(createdBy),2618374.toString,0.0,112.356,SideCode.TowardsDigitizing,(NoCP,JunctionPointCP),(NoCP,JunctionPointCP),List(Point(567713.0,6770541.0,0.0), Point(567758.0,6770644.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,112.356,73303,754558,3,reversed = false,None,1630018852000L,148122120,roadName,None,None,None,None,None,None),
        ProjectLink(1072,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,4375,4399,4375,4399,None,None,Some(createdBy),2618378.toString,0.0,23.905,SideCode.TowardsDigitizing,(JunctionPointCP,NoCP),(JunctionPointCP,NoCP),List(Point(567758.0,6770644.0,0.0), Point(567771.0,6770664.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,23.905,73303,754553,3,reversed = false,None,1630018852000L,148122120,roadName,None,None,None,None,None,None),
        ProjectLink(1073,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,4399,4457,4399,4457,None,None,Some(createdBy),2618361.toString,0.0,56.683,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,RoadAddressCP),List(Point(567771.0,6770664.0,0.0), Point(567803.0,6770711.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,56.683,73303,754560,3,reversed = false,None,1630018852000L,148122120,roadName,None,None,None,None,None,None),
        ProjectLink(1075,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,4457,4561,4457,4561,None,None,Some(createdBy),2618357.toString,0.0,99.048,SideCode.TowardsDigitizing,(NoCP,NoCP),(RoadAddressCP,NoCP),List(Point(567803.0,6770711.0,0.0), Point(567864.0,6770789.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,99.048,14278,708099,3,reversed = false,None,1630018852000L,50308,roadName,None,None,None,None,None,None),
        ProjectLink(1074,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,4457,4558,4457,4558,None,None,Some(createdBy),2618358.toString,0.0,98.608,SideCode.TowardsDigitizing,(NoCP,NoCP),(RoadAddressCP,NoCP),List(Point(567803.0,6770711.0,0.0), Point(567855.0,6770794.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,98.608,73190,743191,3,reversed = false,None,1630018852000L,148127472,roadName,None,None,None,None,None,None),
        ProjectLink(1076,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,4558,4934,4558,4934,None,None,Some(createdBy),2618335.toString,0.0,367.046,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(567855.0,6770794.0,0.0), Point(568063.0,6771097.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,367.046,73190,743193,3,reversed = false,None,1630018852000L,148127472,roadName,None,None,None,None,None,None),
        ProjectLink(1078,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,4561,5012,4561,5012,None,None,Some(createdBy),2618337.toString,0.0,428.813,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(567864.0,6770789.0,0.0), Point(568107.0,6771142.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,428.813,14278,708098,3,reversed = false,None,1630018852000L,50308,roadName,None,None,None,None,None,None),
        ProjectLink(1077,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,4934,4997,4934,4997,None,None,Some(createdBy),2618344.toString,0.0,61.982,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(568063.0,6771097.0,0.0), Point(568098.0,6771148.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,61.982,73190,743194,3,reversed = false,None,1630018852000L,148127472,roadName,None,None,None,None,None,None),
        ProjectLink(1079,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,4997,5179,4997,5179,None,None,Some(createdBy),6929618.toString,0.0,177.61,SideCode.TowardsDigitizing,(NoCP,JunctionPointCP),(NoCP,JunctionPointCP),List(Point(568098.0,6771148.0,0.0), Point(568207.0,6771287.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,177.61,73190,563296,3,reversed = false,None,1630018852000L,148127472,roadName,None,None,None,None,None,None),
        ProjectLink(1081,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,5012,5188,5012,5188,None,None,Some(createdBy),2618153.toString,0.0,166.64,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(568107.0,6771142.0,0.0), Point(568209.0,6771272.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,166.64,14278,528201,3,reversed = false,None,1630018852000L,50308,roadName,None,None,None,None,None,None),
        ProjectLink(1080,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.MinorDiscontinuity,5179,5188,5179,5188,None,None,Some(createdBy),6929633.toString,0.0,8.487,SideCode.TowardsDigitizing,(JunctionPointCP,NoCP),(JunctionPointCP,RoadAddressCP),List(Point(568207.0,6771287.0,0.0), Point(568214.0,6771292.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,8.487,73190,563293,3,reversed = false,None,1630018852000L,148127472,roadName,None,None,None,None,None,None),
        ProjectLink(1083,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,5188,5199,5188,5199,None,None,Some(createdBy),2618153.toString,166.64,177.175,SideCode.TowardsDigitizing,(NoCP,JunctionPointCP),(NoCP,JunctionPointCP),List(Point(568209.0,6771272.0,0.0), Point(568217.0,6771280.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,10.535,81129,574010,3,reversed = false,None,1630018852000L,202228103,roadName,None,None,None,None,None,None),
        ProjectLink(1082,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,5188,5199,5188,5199,None,None,Some(createdBy),6929632.toString,0.0,11.754,SideCode.TowardsDigitizing,(NoCP,JunctionPointCP),(RoadAddressCP,JunctionPointCP),List(Point(568217.0,6771280.0,0.0), Point(568207.0,6771287.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,11.754,80697,574748,3,reversed = false,None,1630018852000L,202230193,roadName,None,None,None,None,None,None),
        ProjectLink(1085,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,5199,5213,5199,5213,None,None,Some(createdBy),2618186.toString,0.0,12.661,SideCode.TowardsDigitizing,(JunctionPointCP,JunctionPointCP),(JunctionPointCP,JunctionPointCP),List(Point(568217.0,6771280.0,0.0), Point(568214.0,6771292.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,12.661,81129,574017,3,reversed = false,None,1630018852000L,202228103,roadName,None,None,None,None,None,None),
        ProjectLink(1084,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,5199,5209,5199,5209,None,None,Some(createdBy),6929630.toString,0.0,9.466,SideCode.TowardsDigitizing,(JunctionPointCP,NoCP),(JunctionPointCP,NoCP),List(Point(568207.0,6771287.0,0.0), Point(568202.0,6771295.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,9.466,80697,574747,3,reversed = false,None,1630018852000L,202230193,roadName,None,None,None,None,None,None),
        ProjectLink(1087,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,5209,5267,5209,5267,None,None,Some(createdBy),2618159.toString,0.0,57.38,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(568202.0,6771295.0,0.0), Point(568191.0,6771349.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,57.38,80697,574750,3,reversed = false,None,1630018852000L,202230193,roadName,None,None,None,None,None,None),
        ProjectLink(1086,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,5213,5223,5213,5223,None,None,Some(createdBy),6929628.toString,0.0,9.37,SideCode.TowardsDigitizing,(JunctionPointCP,NoCP),(JunctionPointCP,NoCP),List(Point(568214.0,6771292.0,0.0), Point(568209.0,6771300.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,9.37,81129,753914,3,reversed = false,None,1630018852000L,202228103,roadName,None,None,None,None,None,None),
        ProjectLink(1088,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,5223,5275,5223,5275,None,None,Some(createdBy),6929627.toString,0.0,49.032,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(568209.0,6771300.0,0.0), Point(568200.0,6771347.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,49.032,81129,753912,3,reversed = false,None,1630018852000L,202228103,roadName,None,None,None,None,None,None),
        ProjectLink(1090,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,5267,5331,5267,5331,None,None,Some(createdBy),2618179.toString,0.0,62.471,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(568191.0,6771349.0,0.0), Point(568240.0,6771383.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,62.471,80697,574744,3,reversed = false,None,1630018852000L,202230193,roadName,None,None,None,None,None,None),
        ProjectLink(1089,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,5275,5326,5275,5326,None,None,Some(createdBy),2618178.toString,0.0,48.914,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(568200.0,6771347.0,0.0), Point(568239.0,6771372.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,48.914,81129,753908,3,reversed = false,None,1630018852000L,202228103,roadName,None,None,None,None,None,None),
        ProjectLink(1091,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,5326,5377,5326,5377,None,None,Some(createdBy),2618182.toString,0.0,48.654,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(568284.0,6771355.0,0.0), Point(568239.0,6771372.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,48.654,81129,574012,3,reversed = false,None,1630018852000L,202228103,roadName,None,None,None,None,None,None),
        ProjectLink(1092,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,5331,5388,5331,5388,None,None,Some(createdBy),2618183.toString,0.0,55.645,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(568290.0,6771361.0,0.0), Point(568240.0,6771383.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,55.645,80697,754650,3,reversed = false,None,1630018852000L,202230193,roadName,None,None,None,None,None,None),
        ProjectLink(1093,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,5377,5419,5377,5419,None,None,Some(createdBy),2618173.toString,0.0,39.387,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(568316.0,6771332.0,0.0), Point(568284.0,6771355.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,39.387,81129,753913,3,reversed = false,None,1630018852000L,202228103,roadName,None,None,None,None,None,None),
        ProjectLink(1094,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,5388,5427,5388,5427,None,None,Some(createdBy),2618174.toString,0.0,38.244,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(568320.0,6771337.0,0.0), Point(568290.0,6771361.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,38.244,80697,754644,3,reversed = false,None,1630018852000L,202230193,roadName,None,None,None,None,None,None),
        ProjectLink(1096,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,5419,5638,5419,5638,None,None,Some(createdBy),2618974.toString,0.0,207.456,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(568498.0,6771234.0,0.0), Point(568316.0,6771332.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,207.456,81129,574011,3,reversed = false,None,1630018852000L,202228103,roadName,None,None,None,None,None,None),
        ProjectLink(1095,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,5427,5637,5427,5637,None,None,Some(createdBy),2618973.toString,0.0,205.311,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(568507.0,6771257.0,0.0), Point(568320.0,6771337.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,205.311,80697,574749,3,reversed = false,None,1630018852000L,202230193,roadName,None,None,None,None,None,None),
        ProjectLink(1097,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.MinorDiscontinuity,5637,5651,5637,5651,None,None,Some(createdBy),2618942.toString,0.0,13.458,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,RoadAddressCP),List(Point(568520.0,6771254.0,0.0), Point(568507.0,6771257.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,13.458,80697,574746,3,reversed = false,None,1630018852000L,202230193,roadName,None,None,None,None,None,None),
        ProjectLink(1098,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.MinorDiscontinuity,5638,5651,5638,5651,None,None,Some(createdBy),2618955.toString,0.0,12.919,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,RoadAddressCP),List(Point(568509.0,6771228.0,0.0), Point(568498.0,6771234.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,12.919,81129,574016,3,reversed = false,None,1630018852000L,202228103,roadName,None,None,None,None,None,None),
        ProjectLink(1099,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,5651,5674,5651,5674,None,None,Some(createdBy),2618948.toString,0.0,21.242,SideCode.TowardsDigitizing,(NoCP,NoCP),(RoadAddressCP,NoCP),List(Point(568565.0,6771209.0,0.0), Point(568587.0,6771210.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,21.242,81365,574755,3,reversed = false,None,1630018852000L,202230207,roadName,None,None,None,None,None,None),
        ProjectLink(1100,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,5651,5675,5651,5675,None,None,Some(createdBy),2618945.toString,0.0,23.343,SideCode.AgainstDigitizing,(NoCP,NoCP),(RoadAddressCP,NoCP),List(Point(568589.0,6771224.0,0.0), Point(568572.0,6771239.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,23.343,80536,574752,3,reversed = false,None,1630018852000L,202230200,roadName,None,None,None,None,None,None),
        ProjectLink(1102,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,5674,5708,5674,5708,None,None,Some(createdBy),2618964.toString,0.0,30.459,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,RoadAddressCP),List(Point(568587.0,6771210.0,0.0), Point(568617.0,6771210.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,30.459,81365,574754,3,reversed = false,None,1630018852000L,202230207,roadName,None,None,None,None,None,None),
        ProjectLink(1101,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,5675,5708,5675,5708,None,None,Some(createdBy),2618963.toString,0.0,31.354,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,RoadAddressCP),List(Point(568617.0,6771210.0,0.0), Point(568589.0,6771224.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,31.354,80536,574753,3,reversed = false,None,1630018852000L,202230200,roadName,None,None,None,None,None,None),
        ProjectLink(1103,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,5708,5910,5708,5910,None,None,Some(createdBy),2618918.toString,0.0,201.305,SideCode.AgainstDigitizing,(NoCP,NoCP),(RoadAddressCP,NoCP),List(Point(568816.0,6771179.0,0.0), Point(568617.0,6771210.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,NormalLinkInterface,201.305,13006,526153,3,reversed = false,None,1630018852000L,49024,roadName,None,None,None,None,None,None),
        ProjectLink(1104,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,5910,6322,5910,6322,None,None,Some(createdBy),11140159.toString,0.0,409.401,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(569219.0,6771105.0,0.0), Point(568816.0,6771179.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,NormalLinkInterface,409.401,13006,706053,3,reversed = false,None,1630018852000L,49024,roadName,None,None,None,None,None,None),
        ProjectLink(1105,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,6322,6331,6322,6331,None,None,Some(createdBy),11142353.toString,0.0,8.883,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(569227.0,6771103.0,0.0), Point(569219.0,6771105.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,NormalLinkInterface,8.883,13006,706055,3,reversed = false,None,1635808409000L,49024,roadName,None,None,None,None,None,None),
        ProjectLink(1106,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,6331,6404,6331,6404,None,None,Some(createdBy),11275750.toString,0.0,72.467,SideCode.AgainstDigitizing,(NoCP,JunctionPointCP),(NoCP,JunctionPointCP),List(Point(569298.0,6771087.0,0.0), Point(569227.0,6771103.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,NormalLinkInterface,72.467,13006,706054,3,reversed = false,None,1630018852000L,49024,roadName,None,None,None,None,None,None),
        ProjectLink(1107,roadNumber,roadPartNumber,Track.Combined,Discontinuity.EndOfRoad,6404,6826,6404,6826,None,None,Some(createdBy),2618768.toString,0.0,417.78,SideCode.AgainstDigitizing,(JunctionPointCP,NoCP),(JunctionPointCP,RoadAddressCP),List(Point(569685.0,6770938.0,0.0), Point(569298.0,6771087.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,NormalLinkInterface,417.78,13006,526157,3,reversed = false,None,1630018852000L,49024,roadName,None,None,None,None,None,None)
      )

      val roadways = Seq(
        Roadway(13006,49024,3821,2,AdministrativeClass.State,Track.Combined,Discontinuity.EndOfRoad,5708,6826,reversed = false,DateTime.parse("2005-06-30T00:00:00.000+03:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2009-12-09T00:00:00.000+02:00"),None),
        Roadway(14278,50308,3821,2,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Continuous,4457,5188,reversed = false,DateTime.parse("2018-07-01T00:00:00.000+03:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2018-07-05T00:00:00.000+03:00"),None),
        Roadway(45590,79184,3821,2,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.MinorDiscontinuity,507,561,reversed = false,DateTime.parse("2018-07-01T00:00:00.000+03:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2018-07-05T00:00:00.000+03:00"),None),
        Roadway(45508,79190,3821,2,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Continuous,0,507,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-30T00:00:00.000+03:00"),None),
        Roadway(48822,6651364,3821,2,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Continuous,812,1656,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-30T00:00:00.000+03:00"),None),
        Roadway(59859,43166556,3821,1,AdministrativeClass.Municipality,Track.Combined,Discontinuity.Continuous,780,909,reversed = false,DateTime.parse("2011-04-01T00:00:00.000+03:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-08T00:00:00.000+02:00"),None),
        Roadway(59736,43166504,3821,1,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Discontinuous,4665,5430,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-30T00:00:00.000+03:00"),None),
        Roadway(70660,148121696,3821,1,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Continuous,222,780,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-30T00:00:00.000+03:00"),None),
        Roadway(70589,148121784,3821,1,AdministrativeClass.Municipality,Track.Combined,Discontinuity.Continuous,3905,4665,reversed = false,DateTime.parse("2011-04-01T00:00:00.000+03:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-08T00:00:00.000+02:00"),None),
        Roadway(72749,148127709,3821,2,AdministrativeClass.Municipality,Track.LeftSide,Discontinuity.MinorDiscontinuity,507,561,reversed = false,DateTime.parse("2018-07-01T00:00:00.000+03:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2018-07-05T00:00:00.000+03:00"),None),
        Roadway(72754,148127686,3821,2,AdministrativeClass.Municipality,Track.LeftSide,Discontinuity.Continuous,812,1656,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-08T00:00:00.000+02:00"),None),
        Roadway(72949,148125051,3821,2,AdministrativeClass.Municipality,Track.Combined,Discontinuity.Continuous,2190,2790,reversed = false,DateTime.parse("2005-06-30T00:00:00.000+03:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-08T00:00:00.000+02:00"),None),
        Roadway(72972,148125617,3821,1,AdministrativeClass.Municipality,Track.Combined,Discontinuity.Continuous,909,1145,reversed = false,DateTime.parse("2011-04-01T00:00:00.000+03:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-08T00:00:00.000+02:00"),None),
        Roadway(72976,148125309,3821,2,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Continuous,1656,2190,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-30T00:00:00.000+03:00"),None),
        Roadway(72981,148124335,3821,1,AdministrativeClass.Municipality,Track.Combined,Discontinuity.Continuous,0,222,reversed = false,DateTime.parse("2011-04-01T00:00:00.000+03:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-08T00:00:00.000+02:00"),None),
        Roadway(73040,148125276,3821,1,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Continuous,2535,2802,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-30T00:00:00.000+03:00"),None),
        Roadway(72853,148125446,3821,1,AdministrativeClass.Municipality,Track.Combined,Discontinuity.Continuous,1520,2535,reversed = false,DateTime.parse("2011-04-01T00:00:00.000+03:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-08T00:00:00.000+02:00"),None),
        Roadway(74788,148128101,3821,2,AdministrativeClass.Municipality,Track.LeftSide,Discontinuity.Continuous,1656,2190,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-08T00:00:00.000+02:00"),None),
        Roadway(74641,148127970,3821,1,AdministrativeClass.Municipality,Track.LeftSide,Discontinuity.Continuous,2535,2802,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-08T00:00:00.000+02:00"),None),
        Roadway(73562,148125036,3821,1,AdministrativeClass.Municipality,Track.Combined,Discontinuity.Continuous,2802,3550,reversed = false,DateTime.parse("2011-04-01T00:00:00.000+03:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-08T00:00:00.000+02:00"),None),
        Roadway(73426,148124341,3821,1,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Continuous,3550,3905,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-30T00:00:00.000+03:00"),None),
        Roadway(73430,148124318,3821,2,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Continuous,2790,3020,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-30T00:00:00.000+03:00"),None),
        Roadway(74754,148128092,3821,2,AdministrativeClass.Municipality,Track.LeftSide,Discontinuity.Continuous,2790,3020,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-08T00:00:00.000+02:00"),None),
        Roadway(73177,148127455,3821,1,AdministrativeClass.Municipality,Track.LeftSide,Discontinuity.Discontinuous,4665,5430,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-08T00:00:00.000+02:00"),None),
        Roadway(73190,148127472,3821,2,AdministrativeClass.Municipality,Track.LeftSide,Discontinuity.MinorDiscontinuity,4457,5188,reversed = false,DateTime.parse("2018-07-01T00:00:00.000+03:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2018-07-05T00:00:00.000+03:00"),None),
        Roadway(73235,148127824,3821,1,AdministrativeClass.Municipality,Track.LeftSide,Discontinuity.Continuous,222,780,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-08T00:00:00.000+02:00"),None),
        Roadway(74536,148128121,3821,1,AdministrativeClass.Municipality,Track.LeftSide,Discontinuity.Continuous,1145,1520,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-08T00:00:00.000+02:00"),None),
        Roadway(73303,148122120,3821,2,AdministrativeClass.Municipality,Track.Combined,Discontinuity.Continuous,3020,4457,reversed = false,DateTime.parse("2005-06-30T00:00:00.000+03:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-08T00:00:00.000+02:00"),None),
        Roadway(75204,148128250,3821,1,AdministrativeClass.Municipality,Track.LeftSide,Discontinuity.Continuous,3550,3905,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-08T00:00:00.000+02:00"),None),
        Roadway(73606,148125555,3821,1,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Continuous,1145,1520,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-30T00:00:00.000+03:00"),None),
        Roadway(73607,148127711,3821,2,AdministrativeClass.Municipality,Track.LeftSide,Discontinuity.Continuous,0,507,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-08T00:00:00.000+02:00"),None),
        Roadway(81119,202230176,3821,2,AdministrativeClass.Municipality,Track.LeftSide,Discontinuity.MinorDiscontinuity,561,812,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2018-07-05T00:00:00.000+03:00"),None),
        Roadway(81129,202228103,3821,2,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.MinorDiscontinuity,5188,5651,reversed = false,DateTime.parse("2018-07-01T00:00:00.000+03:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2018-07-05T00:00:00.000+03:00"),None),
        Roadway(80988,202230183,3821,2,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.MinorDiscontinuity,561,812,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2018-07-05T00:00:00.000+03:00"),None),
        Roadway(80697,202230193,3821,2,AdministrativeClass.Municipality,Track.LeftSide,Discontinuity.MinorDiscontinuity,5188,5651,reversed = false,DateTime.parse("2018-07-01T00:00:00.000+03:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2018-07-05T00:00:00.000+03:00"),None),
        Roadway(81365,202230207,3821,2,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Continuous,5651,5708,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2018-07-05T00:00:00.000+03:00"),None),
        Roadway(80536,202230200,3821,2,AdministrativeClass.Municipality,Track.LeftSide,Discontinuity.Continuous,5651,5708,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2018-07-05T00:00:00.000+03:00"),None)
      )

      val linearLocations = Seq(
        (743191, 1.0, 2618358, 0.0, 98.608, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(Some(4457),Some(RoadAddressCP)),CalibrationPointReference(None,None)), List(Point(567803.211,6770711.35,0.0), Point(567855.431,6770794.886,0.0)), NormalLinkInterface, 148127472, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (574753, 2.0, 2618963, 0.0, 31.354, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(5708),Some(RoadAddressCP))), List(Point(568617.281,6771210.193,0.0), Point(568589.668,6771224.197,0.0)), NormalLinkInterface, 202230200, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (574748, 1.0, 6929632, 0.0, 11.754, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(Some(5188),Some(RoadAddressCP)),CalibrationPointReference(Some(5199),Some(JunctionPointCP))), List(Point(568217.253,6771280.15,0.0), Point(568207.996,6771287.394,0.0)), NormalLinkInterface, 202230193, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (706053, 2.0, 11140159, 0.0, 409.401, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(569219.082,6771105.294,0.0), Point(568816.54,6771179.281,0.0)), NormalLinkInterface, 49024, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (756719, 11.0, 2621332, 0.0, 10.767, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564508.096,6769659.41,0.0), Point(564516.756,6769665.794,0.0)), NormalLinkInterface, 79190, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (574747, 2.0, 6929630, 0.0, 9.466, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(Some(5199),Some(JunctionPointCP)),CalibrationPointReference(None,None)), List(Point(568207.996,6771287.394,0.0), Point(568202.955,6771294.85,0.0)), NormalLinkInterface, 202230193, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (583467, 6.0, 2620635, 0.0, 63.029, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(565329.505,6770203.41,0.0), Point(565391.096,6770216.569,0.0)), NormalLinkInterface, 6651364, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (756711, 2.0, 2621089, 0.0, 24.875, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564172.93,6769549.712,0.0), Point(564196.676,6769557.122,0.0)), NormalLinkInterface, 79190, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (574755, 1.0, 2618948, 0.0, 21.242, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(Some(5651),Some(RoadAddressCP)),CalibrationPointReference(None,None)), List(Point(568565.985,6771209.469,0.0), Point(568586.942,6771210.011,0.0)), NormalLinkInterface, 202230207, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (351769, 1.0, 2621175, 30.585, 83.861, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(561),Some(RoadAddressCP))), List(Point(564534.23,6769690.454,0.0), Point(564547.823,6769741.506,0.0)), NormalLinkInterface, 79184, Some(DateTime.parse("2018-07-05T00:00:00.000+03:00")), None),
        (753914, 3.0, 6929628, 0.0, 9.37, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(Some(5213),Some(JunctionPointCP)),CalibrationPointReference(None,None)), List(Point(568214.732,6771292.557,0.0), Point(568209.675,6771300.445,0.0)), NormalLinkInterface, 202228103, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (754558, 6.0, 2618374, 0.0, 112.356, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(4375),Some(JunctionPointCP))), List(Point(567713.195,6770541.615,0.0), Point(567758.927,6770644.055,0.0)), NormalLinkInterface, 148122120, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (754650, 5.0, 2618183, 0.0, 55.645, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(568290.466,6771361.089,0.0), Point(568240.362,6771383.32,0.0)), NormalLinkInterface, 202230193, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (743248, 3.0, 2621305, 0.0, 91.83, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564188.057,6769570.037,0.0), Point(564275.655,6769597.59,0.0)), NormalLinkInterface, 148127711, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (743247, 6.0, 2621326, 0.0, 67.642, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564343.821,6769619.097,0.0), Point(564408.211,6769639.806,0.0)), NormalLinkInterface, 148127711, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (756714, 3.0, 2621306, 0.0, 76.535, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564196.676,6769557.122,0.0), Point(564269.726,6769579.955,0.0)), NormalLinkInterface, 79190, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (756712, 8.0, 2621337, 0.0, 63.921, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564351.01,6769605.741,0.0), Point(564411.946,6769625.046,0.0)), NormalLinkInterface, 79190, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (743249, 5.0, 2621309, 0.0, 4.571, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564339.459,6769617.732,0.0), Point(564343.821,6769619.097,0.0)), NormalLinkInterface, 148127711, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (754555, 2.0, 2618571, 0.0, 327.094, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(566995.698,6770160.081,0.0), Point(566705.487,6770308.363,0.0)), NormalLinkInterface, 148122120, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (564163, 1.0, 10648023, 64.061, 233.576, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(565390.105,6770226.184,0.0), Point(565557.582,6770251.705,0.0)), NormalLinkInterface, 148128101, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (564157, 3.0, 2618589, 0.0, 28.254, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(566620.18,6770362.463,0.0), Point(566595.988,6770376.558,0.0)), NormalLinkInterface, 148128092, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (753913, 7.0, 2618173, 0.0, 39.387, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(568316.466,6771332.381,0.0), Point(568284.507,6771355.401,0.0)), NormalLinkInterface, 202228103, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (754640, 1.0, 2621196, 0.0, 10.965, SideCode.TowardsDigitizing, 1634598047000L, (CalibrationPointReference(Some(561),Some(RoadAddressCP)),CalibrationPointReference(None,None)), List(Point(564547.578,6769759.089,0.0), Point(564547.676,6769770.054,0.0)), NormalLinkInterface, 202230183, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (742394, 3.0, 2620680, 0.0, 239.642, SideCode.TowardsDigitizing, 1634598047000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(565583.716,6770242.816,0.0), Point(565820.814,6770277.457,0.0)), NormalLinkInterface, 148125309, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (743242, 8.0, 2621323, 0.0, 92.672, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564419.431,6769643.519,0.0), Point(564506.506,6769674.274,0.0)), NormalLinkInterface, 148127711, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (743244, 4.0, 2621314, 0.0, 66.909, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564275.655,6769597.59,0.0), Point(564339.459,6769617.732,0.0)), NormalLinkInterface, 148127711, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (563293, 5.0, 6929633, 0.0, 8.487, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(Some(5179),Some(JunctionPointCP)),CalibrationPointReference(Some(5188),Some(RoadAddressCP))), List(Point(568207.996,6771287.394,0.0), Point(568214.345,6771292.261,0.0)), NormalLinkInterface, 148127472, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (744060, 2.0, 2620700, 0.0, 27.864, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(565557.582,6770251.705,0.0), Point(565585.208,6770255.256,0.0)), NormalLinkInterface, 148128101, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (754642, 4.0, 2620909, 0.0, 19.337, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564561.162,6769966.829,0.0), Point(564563.559,6769986.017,0.0)), NormalLinkInterface, 202230183, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (743231, 1.0, 2620896, 0.0, 17.476, SideCode.TowardsDigitizing, 1634598047000L, (CalibrationPointReference(Some(812),Some(RoadAddressCP)),CalibrationPointReference(None,None)), List(Point(564584.524,6770035.876,0.0), Point(564601.614,6770039.528,0.0)), NormalLinkInterface, 148127686, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (753912, 4.0, 6929627, 0.0, 49.032, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(568209.675,6771300.445,0.0), Point(568200.075,6771347.159,0.0)), NormalLinkInterface, 202228103, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (564158, 5.0, 2618597, 0.0, 63.045, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(3020),Some(RoadAddressCP))), List(Point(566683.044,6770321.233,0.0), Point(566631.183,6770356.686,0.0)), NormalLinkInterface, 148128092, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (756715, 7.0, 2621320, 0.0, 8.601, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564342.786,6769603.221,0.0), Point(564351.01,6769605.741,0.0)), NormalLinkInterface, 79190, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (564160, 2.0, 12675141, 0.0, 34.254, SideCode.AgainstDigitizing, 1652734800000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(566595.776,6770376.698,0.0), Point(566567.36,6770395.367,0.0)), ComplementaryLinkInterface, 148128092, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (574754, 2.0, 2618964, 0.0, 30.459, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(5708),Some(RoadAddressCP))), List(Point(568587.184,6771210.003,0.0), Point(568617.148,6771210.075,0.0)), NormalLinkInterface, 202230207, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (743234, 2.0, 2620884, 0.0, 249.621, SideCode.TowardsDigitizing, 1634598047000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564601.614,6770039.528,0.0), Point(564844.399,6770097.101,0.0)), NormalLinkInterface, 148127686, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (574749, 7.0, 2618973, 0.0, 205.311, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(568507.394,6771257.095,0.0), Point(568320.8,6771337.798,0.0)), NormalLinkInterface, 202230193, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (743230, 3.0, 2620784, 0.0, 155.167, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564844.399,6770097.101,0.0), Point(564995.227,6770133.5,0.0)), NormalLinkInterface, 148127686, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (744061, 3.0, 2620681, 0.0, 236.094, SideCode.TowardsDigitizing, 1634598047000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(565585.208,6770255.256,0.0), Point(565819.046,6770286.572,0.0)), NormalLinkInterface, 148128101, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (574744, 4.0, 2618179, 0.0, 62.471, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(568191.341,6771349.171,0.0), Point(568239.904,6771383.21,0.0)), NormalLinkInterface, 202230193, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (574011, 8.0, 2618974, 0.0, 207.456, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(568498.281,6771235.056,0.0), Point(568316.466,6771332.381,0.0)), NormalLinkInterface, 202228103, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (754560, 8.0, 2618361, 0.0, 56.683, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(4457),Some(RoadAddressCP))), List(Point(567771.643,6770664.271,0.0), Point(567803.211,6770711.35,0.0)), NormalLinkInterface, 148122120, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (756718, 1.0, 2621083, 0.0, 105.305, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(Some(0),Some(RoadAddressCP)),CalibrationPointReference(None,None)), List(Point(564071.924,6769520.061,0.0), Point(564172.93,6769549.712,0.0)), NormalLinkInterface, 79190, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (563344, 9.0, 2621176, 0.0, 22.565, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564506.506,6769674.274,0.0), Point(564522.441,6769690.074,0.0)), NormalLinkInterface, 148127711, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (708099, 1.0, 2618357, 0.0, 99.048, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(Some(4457),Some(RoadAddressCP)),CalibrationPointReference(None,None)), List(Point(567803.211,6770711.35,0.0), Point(567864.089,6770789.104,0.0)), NormalLinkInterface, 50308, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (574703, 5.0, 2618596, 0.0, 59.188, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(3020),Some(RoadAddressCP))), List(Point(566682.903,6770321.273,0.0), Point(566630.204,6770347.582,0.0)), NormalLinkInterface, 148124318, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (744063, 4.0, 2618678, 0.0, 90.718, SideCode.TowardsDigitizing, 1634598047000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(2190),Some(RoadAddressCP))), List(Point(565819.046,6770286.572,0.0), Point(565908.092,6770303.342,0.0)), NormalLinkInterface, 148128101, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (563296, 4.0, 6929618, 0.0, 177.61, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(5179),Some(JunctionPointCP))), List(Point(568098.755,6771148.171,0.0), Point(568207.996,6771287.394,0.0)), NormalLinkInterface, 148127472, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (528201, 3.0, 2618153, 0.0, 166.64, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(568107.22,6771142.294,0.0), Point(568209.873,6771272.633,0.0)), NormalLinkInterface, 50308, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (763365, 1.0, 2620897, 0.0, 18.744, SideCode.TowardsDigitizing, 1634598047000L, (CalibrationPointReference(Some(812),Some(RoadAddressCP)),CalibrationPointReference(None,None)), List(Point(564586.051,6770020.349,0.0), Point(564603.375,6770027.506,0.0)), NormalLinkInterface, 6651364, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (754638, 5.0, 2620911, 0.0, 15.23, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(812),Some(RoadAddressCP))), List(Point(564563.559,6769986.017,0.0), Point(564564.707,6770001.204,0.0)), NormalLinkInterface, 202230183, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (563342, 1.0, 2621176, 22.565, 75.545, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(561),Some(RoadAddressCP))), List(Point(564522.441,6769690.074,0.0), Point(564537.159,6769739.538,0.0)), NormalLinkInterface, 148127709, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (574016, 9.0, 2618955, 0.0, 12.919, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(5651),Some(RoadAddressCP))), List(Point(568509.719,6771228.123,0.0), Point(568498.681,6771234.836,0.0)), NormalLinkInterface, 202228103, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (743246, 1.0, 2621084, 0.0, 104.894, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(Some(0),Some(RoadAddressCP)),CalibrationPointReference(None,None)), List(Point(564063.384,6769533.617,0.0), Point(564164.119,6769562.655,0.0)), NormalLinkInterface, 148127711, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (574017, 2.0, 2618186, 0.0, 12.661, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(Some(5199),Some(JunctionPointCP)),CalibrationPointReference(Some(5213),Some(JunctionPointCP))), List(Point(568217.253,6771280.15,0.0), Point(568214.732,6771292.557,0.0)), NormalLinkInterface, 202228103, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (562440, 4.0, 2618480, 0.0, 133.577, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(2790),Some(RoadAddressCP))), List(Point(566482.601,6770432.12,0.0), Point(566351.082,6770439.097,0.0)), NormalLinkInterface, 148125051, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (574701, 4.0, 2618604, 0.0, 14.931, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(566630.204,6770347.582,0.0), Point(566617.109,6770354.755,0.0)), NormalLinkInterface, 148124318, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (754644, 6.0, 2618174, 0.0, 38.244, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(568320.8,6771337.798,0.0), Point(568290.466,6771361.089,0.0)), NormalLinkInterface, 202230193, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (754557, 4.0, 2618392, 0.0, 105.993, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(Some(4136),Some(JunctionPointCP)),CalibrationPointReference(None,None)), List(Point(567657.912,6770430.601,0.0), Point(567706.521,6770524.419,0.0)), NormalLinkInterface, 148122120, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (462093, 1.0, 2621199, 0.0, 10.459, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(Some(561),Some(RoadAddressCP)),CalibrationPointReference(None,None)), List(Point(564537.828,6769760.388,0.0), Point(564539.283,6769770.745,0.0)), NormalLinkInterface, 202230176, Some(DateTime.parse("2018-07-05T00:00:00.000+03:00")), None),
        (562439, 1.0, 2618671, 0.0, 309.535, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(Some(2190),Some(RoadAddressCP)),CalibrationPointReference(None,None)), List(Point(565908.092,6770303.342,0.0), Point(566205.396,6770388.445,0.0)), NormalLinkInterface, 148125051, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (754559, 5.0, 2618315, 0.0, 18.446, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(567706.521,6770524.419,0.0), Point(567713.195,6770541.615,0.0)), NormalLinkInterface, 148122120, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (564156, 1.0, 2618584, 0.0, 93.474, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(Some(2790),Some(RoadAddressCP)),CalibrationPointReference(None,None)), List(Point(566566.945,6770395.596,0.0), Point(566482.601,6770432.12,0.0)), NormalLinkInterface, 148128092, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (742395, 4.0, 2618679, 0.0, 91.243, SideCode.TowardsDigitizing, 1634598047000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(2190),Some(RoadAddressCP))), List(Point(565820.814,6770277.457,0.0), Point(565908.092,6770303.342,0.0)), NormalLinkInterface, 148125309, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (756717, 9.0, 2621328, 0.0, 13.674, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564411.946,6769625.046,0.0), Point(564424.981,6769629.176,0.0)), NormalLinkInterface, 79190, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (743232, 5.0, 2620726, 0.0, 335.841, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(565000.8,6770134.863,0.0), Point(565327.494,6770212.633,0.0)), NormalLinkInterface, 148127686, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (562493, 1.0, 2620635, 63.029, 233.406, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(565391.096,6770216.569,0.0), Point(565559.534,6770239.421,0.0)), NormalLinkInterface, 148125309, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (754553, 7.0, 2618378, 0.0, 23.905, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(Some(4375),Some(JunctionPointCP)),CalibrationPointReference(None,None)), List(Point(567758.927,6770644.055,0.0), Point(567771.643,6770664.271,0.0)), NormalLinkInterface, 148122120, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (743233, 4.0, 2620798, 0.0, 5.737, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564995.227,6770133.5,0.0), Point(565000.8,6770134.863,0.0)), NormalLinkInterface, 148127686, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (756720, 6.0, 2621311, 0.0, 50.696, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564294.486,6769587.821,0.0), Point(564342.786,6769603.221,0.0)), NormalLinkInterface, 79190, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (563330, 6.0, 10648023, 0.0, 64.061, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(565327.494,6770212.633,0.0), Point(565390.046,6770226.171,0.0)), NormalLinkInterface, 148127686, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (706054, 4.0, 11275750, 0.0, 72.467, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(6404),Some(JunctionPointCP))), List(Point(569298.536,6771087.834,0.0), Point(569227.758,6771103.389,0.0)), NormalLinkInterface, 49024, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (743245, 7.0, 2621329, 0.0, 11.818, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564408.211,6769639.806,0.0), Point(564419.431,6769643.519,0.0)), NormalLinkInterface, 148127711, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (574750, 3.0, 2618159, 0.0, 57.38, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(568202.694,6771295.236,0.0), Point(568191.221,6771348.81,0.0)), NormalLinkInterface, 202230193, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (763364, 5.0, 2620729, 0.0, 335.083, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(565004.057,6770123.957,0.0), Point(565329.505,6770203.41,0.0)), NormalLinkInterface, 6651364, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (574752, 1.0, 2618945, 0.0, 23.343, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(Some(5651),Some(RoadAddressCP)),CalibrationPointReference(None,None)), List(Point(568589.408,6771224.42,0.0), Point(568572.459,6771239.949,0.0)), NormalLinkInterface, 202230200, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (526153, 1.0, 2618918, 0.0, 201.305, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(Some(5708),Some(RoadAddressCP)),CalibrationPointReference(None,None)), List(Point(568816.238,6771179.327,0.0), Point(568617.607,6771210.055,0.0)), NormalLinkInterface, 49024, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (574012, 6.0, 2618182, 0.0, 48.654, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(568284.507,6771355.401,0.0), Point(568239.515,6771372.293,0.0)), NormalLinkInterface, 202228103, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (763362, 3.0, 2620785, 0.0, 155.679, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564846.183,6770086.623,0.0), Point(564997.683,6770122.45,0.0)), NormalLinkInterface, 6651364, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (742393, 2.0, 2620697, 0.0, 24.014, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(565559.937,6770239.468,0.0), Point(565583.716,6770242.816,0.0)), NormalLinkInterface, 148125309, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (562441, 2.0, 2618703, 0.0, 135.096, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(566205.396,6770388.445,0.0), Point(566332.427,6770434.134,0.0)), NormalLinkInterface, 148125051, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (562438, 3.0, 2618716, 0.0, 19.208, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(566332.517,6770434.167,0.0), Point(566350.881,6770439.044,0.0)), NormalLinkInterface, 148125051, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (756721, 12.0, 2621175, 0.0, 30.585, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564516.756,6769665.794,0.0), Point(564534.23,6769690.454,0.0)), NormalLinkInterface, 79190, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (574704, 1.0, 2618583, 0.0, 91.162, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(Some(2790),Some(RoadAddressCP)),CalibrationPointReference(None,None)), List(Point(566561.533,6770386.961,0.0), Point(566482.601,6770432.12,0.0)), NormalLinkInterface, 148124318, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (743193, 2.0, 2618335, 0.0, 367.046, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(567855.431,6770794.886,0.0), Point(568063.083,6771097.483,0.0)), NormalLinkInterface, 148127472, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (763363, 4.0, 2620802, 0.0, 6.55, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564997.683,6770122.45,0.0), Point(565004.057,6770123.957,0.0)), NormalLinkInterface, 6651364, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (706055, 3.0, 11142353, 0.0, 8.883, SideCode.AgainstDigitizing, 1635808409000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(569227.758,6771103.389,0.0), Point(569219.082,6771105.294,0.0)), NormalLinkInterface, 49024, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (743194, 3.0, 2618344, 0.0, 61.982, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(568063.083,6771097.483,0.0), Point(568098.755,6771148.171,0.0)), NormalLinkInterface, 148127472, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (574010, 1.0, 2618153, 166.64, 177.175, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(5199),Some(JunctionPointCP))), List(Point(568209.873,6771272.633,0.0), Point(568217.253,6771280.15,0.0)), NormalLinkInterface, 202228103, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (754556, 3.0, 2618435, 0.0, 754.015, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(4136),Some(JunctionPointCP))), List(Point(566995.698,6770160.081,0.0), Point(567657.912,6770430.601,0.0)), NormalLinkInterface, 148122120, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (574705, 3.0, 2618600, 0.0, 28.861, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(566617.109,6770354.755,0.0), Point(566591.789,6770368.606,0.0)), NormalLinkInterface, 148124318, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (756716, 4.0, 2621315, 0.0, 11.151, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564269.726,6769579.955,0.0), Point(564280.309,6769583.47,0.0)), NormalLinkInterface, 79190, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (708098, 2.0, 2618337, 0.0, 428.813, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(567864.089,6770789.104,0.0), Point(568107.22,6771142.294,0.0)), NormalLinkInterface, 50308, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (754554, 1.0, 2618610, 0.0, 25.827, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(Some(3020),Some(RoadAddressCP)),CalibrationPointReference(None,None)), List(Point(566705.487,6770308.363,0.0), Point(566683.078,6770321.204,0.0)), NormalLinkInterface, 148122120, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (462096, 4.0, 2620912, 0.0, 17.014, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(812),Some(RoadAddressCP))), List(Point(564554.132,6769986.065,0.0), Point(564552.128,6770002.918,0.0)), NormalLinkInterface, 202230176, Some(DateTime.parse("2018-07-05T00:00:00.000+03:00")), None),
        (574746, 8.0, 2618942, 0.0, 13.458, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(5651),Some(RoadAddressCP))), List(Point(568520.493,6771254.735,0.0), Point(568507.698,6771257.029,0.0)), NormalLinkInterface, 202230193, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (754641, 2.0, 2621223, 0.0, 90.539, SideCode.TowardsDigitizing, 1634598047000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564547.676,6769770.054,0.0), Point(564553.319,6769860.417,0.0)), NormalLinkInterface, 202230183, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (763361, 2.0, 2620883, 0.0, 249.906, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564603.375,6770027.506,0.0), Point(564846.183,6770086.623,0.0)), NormalLinkInterface, 6651364, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (462095, 3.0, 2621210, 0.0, 167.07, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564542.894,6769819.375,0.0), Point(564554.132,6769986.065,0.0)), NormalLinkInterface, 202230176, Some(DateTime.parse("2018-07-05T00:00:00.000+03:00")), None),
        (574702, 2.0, 12675142, 0.0, 35.227, SideCode.AgainstDigitizing, 1652734800000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(566591.595,6770368.723,0.0), Point(566561.67,6770386.875,0.0)), ComplementaryLinkInterface, 148124318, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (526157, 5.0, 2618768, 0.0, 417.78, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(Some(6404),Some(JunctionPointCP)),CalibrationPointReference(Some(6826),Some(RoadAddressCP))), List(Point(569685.205,6770938.98,0.0), Point(569298.536,6771087.834,0.0)), NormalLinkInterface, 49024, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (756710, 5.0, 2621318, 0.0, 14.83, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564280.309,6769583.47,0.0), Point(564294.486,6769587.821,0.0)), NormalLinkInterface, 79190, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (753908, 5.0, 2618178, 0.0, 48.914, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(568200.075,6771347.159,0.0), Point(568239.515,6771372.293,0.0)), NormalLinkInterface, 202228103, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (564159, 4.0, 2618605, 0.0, 12.173, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(566631.03,6770356.766,0.0), Point(566620.401,6770362.337,0.0)), NormalLinkInterface, 148128092, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (754639, 3.0, 2620906, 0.0, 106.701, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564553.319,6769860.417,0.0), Point(564561.162,6769966.829,0.0)), NormalLinkInterface, 202230183, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (743250, 2.0, 2621085, 0.0, 25.05, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564164.119,6769562.655,0.0), Point(564188.057,6769570.037,0.0)), NormalLinkInterface, 148127711, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (756713, 10.0, 2621322, 0.0, 88.702, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564424.981,6769629.176,0.0), Point(564508.096,6769659.41,0.0)), NormalLinkInterface, 79190, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (462094, 2.0, 2621184, 0.0, 48.764, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564539.283,6769770.745,0.0), Point(564542.894,6769819.375,0.0)), NormalLinkInterface, 202230176, Some(DateTime.parse("2018-07-05T00:00:00.000+03:00")), None)
      ).map(l => LinearLocation(l._1,l._2,l._3.toString,l._4,l._5,l._6,l._7,l._8,l._9,l._10,l._11,l._12,l._13))

      buildTestDataForProject(Some(project), Some(roadways), Some(linearLocations), Some(projectLinks))

      val calculated = defaultSectionCalculatorStrategy.assignMValues(Seq(), projectLinks.filterNot(_.status == LinkStatus.Terminated), Seq.empty[UserDefinedCalibrationPoint])
      val calculatedAddressValues = calculated.map(pl => (pl.startAddrMValue, pl.endAddrMValue, pl.track.value))

      // Pre-calculated
      val targetAddresses = Seq((0, 109, 2), (109, 135, 2), (135, 230, 2), (230, 300, 2), (300, 305, 2), (305, 375, 2), (375, 387, 2), (387, 483, 2), (483, 507, 2), (507, 561, 2), (561, 572, 2), (572, 622, 2), (622, 794, 2), (794, 812, 2), (812, 830, 2), (830, 1084, 2), (1084, 1242, 2), (1242, 1248, 2), (1248, 1590, 2), (1590, 1656, 2), (1656, 1829, 2), (1829, 1857, 2), (1857, 2098, 2), (2098, 2190, 2), (2790, 2881, 2), (2881, 2882, 2), (2882, 2910, 2), (2910, 2922, 2), (2922, 2985, 2), (4422, 4523, 2), (4523, 4899, 2), (4899, 4962, 2), (4962, 5144, 2), (5144, 5153, 2), (5153, 5164, 2), (5164, 5174, 2), (5174, 5232, 2), (5232, 5296, 2), (5296, 5353, 2), (5353, 5392, 2), (5392, 5602, 2), (5602, 5616, 2), (5616, 5639, 2), (5639, 5640, 2), (5640, 5673, 2), (0, 107, 1), (107, 132, 1), (132, 210, 1), (210, 221, 1), (221, 236, 1), (236, 288, 1), (288, 296, 1), (296, 361, 1), (361, 375, 1), (375, 465, 1), (465, 476, 1), (476, 507, 1), (507, 561, 1), (561, 572, 1), (572, 666, 1), (666, 776, 1), (776, 796, 1), (796, 812, 1), (812, 831, 1), (831, 1086, 1), (1086, 1244, 1), (1244, 1251, 1), (1251, 1592, 1), (1592, 1656, 1), (1656, 1829, 1), (1829, 1854, 1), (1854, 2098, 1), (2098, 2190, 1), (2790, 2882, 1), (2882, 2911, 1), (2911, 2926, 1), (2926, 2985, 1), (4422, 4526, 1), (4526, 4977, 1), (4977, 5153, 1), (5153, 5164, 1), (5164, 5178, 1), (5178, 5188, 1), (5188, 5240, 1), (5240, 5291, 1), (5291, 5342, 1), (5342, 5384, 1), (5384, 5603, 1), (5603, 5616, 1), (5616, 5639, 1), (5639, 5640, 1), (5640, 5673, 1), (2190, 2501, 0), (2501, 2637, 0), (2637, 2656, 0), (2656, 2790, 0), (2985, 3011, 0), (3011, 3341, 0), (3341, 4101, 0), (4101, 4208, 0), (4208, 4227, 0), (4227, 4340, 0), (4340, 4364, 0), (4364, 4422, 0), (5673, 5875, 0), (5875, 6287, 0), (6287, 6296, 0), (6296, 6369, 0), (6369, 6791, 0))
      // Group by track and startAddrMValue
      val t = (calculatedAddressValues ++ targetAddresses).groupBy(v => (v._3,v._1))
      // Check foreach calculated there exist match in targetAddresses
      t.values.foreach(g => {
        // Match matchEndAddrMValue
        g should have size 2
        g.head._2 should be(g.last._2)
      })
    }
  }

  test("Test findStartingPoints When transfering some links from one two track road to the beginning of another two track road that is not been handled yet Then the road should still maintain the previous existing direction") {
    runWithRollback {
      val geomRoad1TransferLeft = Seq(Point(0.0, 5.0), Point(10.0, 5.0))
      val geomRoad1TransferRight = Seq(Point(0.0, 0.0), Point(10.0, 0.0))
      val geomRoad2NotHandledLeft = Seq(Point(10.0, 5.0), Point(20.0, 3.0))
      val geomRoad2NotHandledRight = Seq(Point(10.0, 0.0), Point(20.0, 3.0))
      val geomRoad2NotHandledCombined = Seq(Point(20.0, 3.0), Point(30.0, 3.0))
      val plId = Sequences.nextProjectLinkId

      val Road1TransferLeftLink = ProjectLink(plId + 1, 9999L, 1L, Track.LeftSide, Discontinuity.Continuous, 0L, 10L, 890L, 900L, None, None, None, 12345L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomRoad1TransferLeft, 0L, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRoad1TransferLeft), 0L, 0, 0, reversed = false, None, 86400L)
      val Road1TransferRightLink = ProjectLink(plId + 2, 9999L, 1L, Track.RightSide, Discontinuity.Continuous, 890L, 900L, 890L, 900L, None, None, None, 12346L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomRoad1TransferRight, 0L, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRoad1TransferRight), 0L, 0, 0, reversed = false, None, 86400L)
      val Road2NotHandledLeftLink = ProjectLink(plId + 3, 9999L, 1L, Track.LeftSide, Discontinuity.Continuous, 0L, 10L, 0L, 10L, None, None, None, 12345L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomRoad2NotHandledLeft, 0L, LinkStatus.NotHandled, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRoad2NotHandledLeft), 0L, 0, 0, reversed = false, None, 86400L)
      val Road2NotHandledRightLink = ProjectLink(plId + 4, 9999L, 1L, Track.RightSide, Discontinuity.Continuous, 0L, 10L, 0L, 10L, None, None, None, 12346L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomRoad2NotHandledRight, 0L, LinkStatus.NotHandled, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRoad2NotHandledRight), 0L, 0, 0, reversed = false, None, 86400L)
      val Road2NotHandledCombinedLink = ProjectLink(plId + 4, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 10L, 20L, 10L, 20L, None, None, None, 12346L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomRoad2NotHandledCombined, 0L, LinkStatus.NotHandled, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRoad2NotHandledCombined), 0L, 0, 0, reversed = false, None, 86400L)


      val otherProjectLinks = Seq(Road2NotHandledRightLink, Road2NotHandledLeftLink, Road1TransferLeftLink, Road2NotHandledCombinedLink, Road1TransferRightLink)
      val newProjectLinks = Seq()

      val startingPointsForCalculations = defaultSectionCalculatorStrategy.findStartingPoints(newProjectLinks, otherProjectLinks, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
      startingPointsForCalculations should be((Road1TransferRightLink.geometry.head, Road1TransferLeftLink.geometry.head))
    }
  }

  test("Test findStartingPoints " +
       "When a combined road has a loopend" +
       "Then startingpoint from triple connection should be found.") {
    runWithRollback {

      val triplePoint = Point(371826, 6669765)
      val geom1 = Seq(Point(372017, 6669721), triplePoint)// Point(371826, 6669765) found in three geometries
      val geom2 = Seq(Point(372017, 6669721), Point(372026, 6669819))
      val geom3 = Seq(Point(372026, 6669819), Point(371880, 6669863))
      val geom4 = Seq(triplePoint, Point(371880, 6669863))
      val geom5 = Seq(Point(371704, 6669673), triplePoint)
      val geom6 = Seq(Point(371637, 6669626), Point(371704, 6669673))

      val plId = Sequences.nextProjectLinkId

      val otherProjectLinks = Seq(
        ProjectLink(plId + 1, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12345L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geom1, 0L, LinkStatus.Transfer, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom1), 0L, 0, 0, reversed = true, None, 86400L),
        ProjectLink(plId + 2, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12346L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geom2, 0L, LinkStatus.Transfer, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom2), 0L, 0, 0, reversed = true, None, 86400L),
        ProjectLink(plId + 3, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12347L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geom3, 0L, LinkStatus.Transfer, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom3), 0L, 0, 0, reversed = true, None, 86400L),
        ProjectLink(plId + 4, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12348L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geom4, 0L, LinkStatus.Transfer, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom4), 0L, 0, 0, reversed = true, None, 86400L),
        ProjectLink(plId + 4, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12349L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geom5, 0L, LinkStatus.Transfer, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom5), 0L, 0, 0, reversed = true, None, 86400L),
        ProjectLink(plId + 5, 9999L, 1L, Track.Combined, Discontinuity.Discontinuous, 0L, 0L, 0L, 0L, None, None, None, 12350L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geom6, 0L, LinkStatus.Transfer, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom6), 0L, 0, 0, reversed = true, None, 86400L)
      )

      val newProjectLinks = Seq()

      // Validate correct starting point for reversed combined road.
      val startingPointsForCalculations = defaultSectionCalculatorStrategy.findStartingPoints(newProjectLinks, otherProjectLinks, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
      startingPointsForCalculations should be (triplePoint, triplePoint)
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

      val projectLink1 = ProjectLink(plId + 1, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 15L, 15L, 30L, None, None, None, 12345L.toString, 0.0, 15.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransfer1, 0L, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransfer1), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLink2 = ProjectLink(plId + 2, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 15L, 30L, 30L, 45L, None, None, None, 12346L.toString, 0.0, 15.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransfer2, 0L, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransfer2), 0L, 0, 0, reversed = false, None, 86400L)

      val geomNew3 = Seq(Point(40.0, 10.0), Point(30.0, 20.0))

      val projectLinkNew3 = ProjectLink(plId + 3, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12347L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomNew3, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew3), 0L, 0, 0, reversed = false, None, 86400L)

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

      val projectLink1 = ProjectLink(plId + 1, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 15L, 15L, 30L, None, None, None, 12345L.toString, 0.0, 15.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransfer1, 0L, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransfer1), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLink2 = ProjectLink(plId + 2, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 15L, 30L, 30L, 45L, None, None, None, 12346L.toString, 0.0, 15.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransfer2, 0L, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransfer2), 0L, 0, 0, reversed = false, None, 86400L)

      val geomNew3 = Seq(Point(30.0, 20.0), Point(40.0, 10.0))

      val projectLinkNew3 = ProjectLink(plId + 3, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12347L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomNew3, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew3), 0L, 0, 0, reversed = false, None, 86400L)

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

      val projectLink1 = ProjectLink(plId + 1, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 15L, 0L, 15L, None, None, None, 12345L.toString, 0.0, 15.0, sideCode, (NoCP, NoCP), (NoCP, NoCP), geom1, 0L, linkStatus, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom1), 0L, 0, 0, reversed = false, None, 86400L)

      val geomNew2 = Seq(Point(30.0, 0.0), Point(20.0, 10.0))

      // Notice that discontinuity value should not affect calculations, which are based on geometry. That's why we have here this value "Continuous".
      val projectLinkNew2 = ProjectLink(plId + 3, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12347L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomNew2, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew2), 0L, 0, 0, reversed = false, None, 86400L)

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

      val projectLink1 = ProjectLink(plId + 1, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 15L, 0L, 15L, None, None, None, 12345L.toString, 0.0, 15.0, sideCode, (NoCP, NoCP), (NoCP, NoCP), geom1, 0L, linkStatus, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom1), 0L, 0, 0, reversed = false, None, 86400L)

      val geomNew2 = Seq(Point(30.0, 20.0), Point(20.0, 30.0))
      val geomNew3 = Seq(Point(50.0, 0.0), Point(40.0, 10.0))

      // Notice that discontinuity value should not affect calculations, which are based on geometry. That's why we have here this value "Continuous".
      val projectLinkNew2 = ProjectLink(plId + 2, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12347L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomNew2, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew2), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkNew3 = ProjectLink(plId + 3, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12347L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomNew3, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew3), 0L, 0, 0, reversed = false, None, 86400L)

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
      val projectLink1 = ProjectLink(plId + 1, 9999L, 1L, Track.Combined, Discontinuity.Continuous, startAddr1, endAddr1, startAddr1, endAddr1, None, None, None, 12345L.toString, 0.0, 15.0, sideCode, (NoCP, NoCP), (NoCP, NoCP), geom1, 0L, linkStatus, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom1), 0L, 0, 0, reversed = false, None, 86400L)

      val startAddr34 = if (sideCode == SideCode.TowardsDigitizing) 0L else 15L
      val endAddr34 = if (sideCode == SideCode.TowardsDigitizing) 15L else 30L
      val projectLinkNew3 = ProjectLink(plId + 3, 9999L, 1L, if (sideCode == SideCode.TowardsDigitizing) Track.LeftSide else Track.RightSide, Discontinuity.Continuous, startAddr34, endAddr34, startAddr34, endAddr34, None, None, None, 12347L.toString, 0.0, 0.0, sideCode, (NoCP, NoCP), (NoCP, NoCP), geom3, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom3), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkNew4 = ProjectLink(plId + 4, 9999L, 1L, if (sideCode == SideCode.TowardsDigitizing) Track.RightSide else Track.LeftSide, Discontinuity.Continuous, startAddr34, endAddr34, startAddr34, endAddr34, None, None, None, 12348L.toString, 0.0, 0.0, sideCode, (NoCP, NoCP), (NoCP, NoCP), geom4, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom4), 0L, 0, 0, reversed = false, None, 86400L)

      val geomNew2 = Seq(Point(30.0, 20.0), Point(20.0, 30.0))

      // Notice that discontinuity value should not affect calculations, which are based on geometry. That's why we have here this value "Continuous".
      val projectLinkNew2 = ProjectLink(plId + 2, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12346L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomNew2, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew2), 0L, 0, 0, reversed = false, None, 86400L)

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

      val projectLink1 = ProjectLink(plId + 1, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 15L, 0L, 15L, None, None, None, 12345L.toString, 0.0, 15.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransfer1, 0L, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransfer1), 0L, 0, 0, reversed = false, None, 86400L)

      val geomNew2 = Seq(Point(35.0, 0.0), Point(25.0, 10.0))
      val geomNew3 = Seq(Point(25.0, 0.0), Point(15.0, 10.0))

      val projectLinkNew2 = ProjectLink(plId + 2, 9999L, 1L, Track.RightSide, Discontinuity.MinorDiscontinuity, 0L, 0L, 0L, 0L, None, None, None, 12347L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomNew2, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew2), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkNew3 = ProjectLink(plId + 3, 9999L, 1L, Track.LeftSide, Discontinuity.MinorDiscontinuity, 0L, 0L, 0L, 0L, None, None, None, 12347L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomNew3, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew3), 0L, 0, 0, reversed = false, None, 86400L)


      val otherProjectLinks = Seq(projectLink1)
      val newProjectLinks = Seq(projectLinkNew2, projectLinkNew3)

      val startingPointsForCalculations = defaultSectionCalculatorStrategy.findStartingPoints(newProjectLinks, otherProjectLinks, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
      startingPointsForCalculations should be((geomNew2.head, geomNew3.head))
    }
  }

  test("Test findStartingPoints When creating New Left track link which have its geometry reversed and Transfer Combined to RightSide before existing NotHandled link road Then the road should still maintain the previous existing direction") {
    runWithRollback {
      val geomNotHandled1 = Seq(Point(10.0, 5.0), Point(20.0, 5.0))
      val plId = Sequences.nextProjectLinkId

      val projectLink1 = ProjectLink(plId + 1, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 10L, 25L, 10L, 25L, None, None, None, 12345L.toString, 0.0, 15.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNotHandled1, 0L, LinkStatus.NotHandled, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNotHandled1), 0L, 0, 0, reversed = false, None, 86400L)

      val geomNewLeft = Seq(Point(10.0, 5.0), Point(0.0, 10.0))
      val geomTransferRight = Seq(Point(0.0, 0.0), Point(10.0, 5.0))

      val projectLinkTransfer = ProjectLink(plId + 2, 9999L, 1L, Track.RightSide, Discontinuity.Continuous, 0L, 10L, 0L, 10L, None, None, None, 12347L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferRight, 0L, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkNew = ProjectLink(plId + 3, 9999L, 1L, Track.LeftSide, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12347L.toString, 0.0, 10.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomNewLeft, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewLeft), 0L, 0, 0, reversed = false, None, 86400L)

      val otherProjectLinks = Seq(projectLink1, projectLinkTransfer)
      val projectLinks = Seq(projectLinkNew)

      val startingPointsForCalculations = defaultSectionCalculatorStrategy.findStartingPoints(projectLinks, otherProjectLinks, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
      startingPointsForCalculations should be((geomTransferRight.head, geomNewLeft.last))
    }
  }

  test("Test findStartingPoints When adding two (New) links before and after existing transfer links(s) Then the road should maintain the previous direction") {
    runWithRollback {
      val geomTransferComb1 = Seq(Point(40.0, 30.0), Point(30.0, 40.0))
      val geomTransferComb2 = Seq(Point(30.0, 40.0), Point(20.0, 50.0))
      val plId = Sequences.nextProjectLinkId

      val projectLinkComb1 = ProjectLink(plId + 1, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 15L, 30L, 15L, 30L, None, None, None, 12345L.toString, 0.0, 15.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferComb1, 0L, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferComb1), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkComb2 = ProjectLink(plId + 2, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 30L, 45L, 30L, 45L, None, None, None, 12346L.toString, 0.0, 15.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferComb2, 0L, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferComb2), 0L, 0, 0, reversed = false, None, 86400L)

      val geomNewCombBefore = Seq(Point(50.0, 20.0), Point(40.0, 30.0))
      val geomNewCombAfter = Seq(Point(10.0, 60.0), Point(20.0, 60.0))

      val projectLinkCombNewBefore = ProjectLink(plId + 3, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12347L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomNewCombBefore, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewCombBefore), 0L, 0, 0, reversed = false, None, 86400L)

      val projectLinkCombNewAfter = ProjectLink(plId + 4, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12347L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomNewCombAfter, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewCombAfter), 0L, 0, 0, reversed = false, None, 86400L)

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

  /* Unnecessary roadwaynumber checkings? VIITE-2348, DefaultSectionCalculatorStrategy.scala: 273, adjustableToRoadwayNumberAttribution. */
//  test("Test defaultSectionCalculatorStrategy.assignMValues() and the attribution of roadway_numbers for new Left Right sections with same number of links Then " +
//    "if there are for e.g. 3 (three) consecutive links with same roadway_number (and all Transfer status), the first 3 (three) opposite track links (with all New status) should share some new generated roadway_number between them") {
//    runWithRollback {
//      //geoms
//      //Left
//      //before roundabout
//      val geomTransferLeft1 = Seq(Point(0.0, 5.0), Point(5.0, 5.0))
//      val geomTransferLeft2 = Seq(Point(5.0, 5.0), Point(10.0, 5.0))
//      //after roundabout
//      val geomTransferLeft3 = Seq(Point(10.0, 5.0), Point(11.0, 10.0))
//      val geomTransferLeft4 = Seq(Point(11.0, 10.0), Point(13.0, 15.0))
//      val geomTransferLeft5 = Seq(Point(13.0, 15.0), Point(15.0, 25.0))
//
//      //Right
//      //before roundabout
//      val geomNewRight1 = Seq(Point(0.0, 0.0), Point(5.0, 0.0))
//      val geomNewRight2 = Seq(Point(5.0, 0.0), Point(10.0, 0.0))
//      //after roundabout
//      val geomTransferRight3 = Seq(Point(20.0, 0.0), Point(19.0, 5.0))
//      val geomTransferRight4 = Seq(Point(19.0, 5.0), Point(18.0, 10.0))
//      val geomTransferRight5 = Seq(Point(18.0, 10.0), Point(15.0, 25.0))
//
//      val projectId = Sequences.nextViiteProjectId
//      val roadwayId = Sequences.nextRoadwayId
//      val linearLocationId = Sequences.nextLinearLocationId
//      val roadwayNumber = Sequences.nextRoadwayNumber
//      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
//        "", Seq(), Seq(), None, None)
//
//      //projectlinks
//
//      //before roundabout
//
//      //Left Transfer
//      val projectLinkLeft1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 5L, 0L, 5L, None, None, None, 12345L, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferLeft1, projectId, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferLeft1), roadwayId, linearLocationId, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber)
//      val projectLinkLeft2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.ParallelLink, 5L, 10L, 5L, 10L, None, None, None, 12346L, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferLeft2, projectId, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferLeft2), roadwayId + 1, linearLocationId + 1, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber)
//      //Right New
//      val projectLinkRight1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12347L, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNewRight1, projectId, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewRight1), 0, 0, 8L, reversed = false, None, 86400L)
//      val projectLinkRight2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.MinorDiscontinuity, 0L, 0L, 0L, 0L, None, None, None, 12348L, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNewRight2, projectId, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewRight2), 0, 0, 8L, reversed = false, None, 86400L)
//
//      //after roundabout
//
//      //Left New
//      val projectLinkLeft3 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12349L, 0.0, 5.1, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferLeft3, projectId, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferLeft3), 0, 0, 8L, reversed = false, None, 86400L)
//      val projectLinkLeft4 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12350L, 0.0, 5.3, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferLeft4, projectId, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferLeft4), 0, 0, 8L, reversed = false, None, 86400L)
//      val projectLinkLeft5 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12351L, 0.0, 10.1, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferLeft5, projectId, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferLeft5), 0, 0, 8L, reversed = false, None, 86400L)
//      //Right Transfer
//      val projectLinkRight3 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 5L, 0L, 5L, None, None, None, 12352L, 0.0, 5.1, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferRight3, projectId, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight3), roadwayId + 2, linearLocationId + 2, 8L, reversed = false, None, 86400L, roadwayNumber = Sequences.nextRoadwayNumber)
//      val nextRwNumber = Sequences.nextRoadwayNumber
//      val projectLinkRight4 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 5L, 10L, 5L, 10L, None, None, None, 12352L, 0.0, 5.1, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferRight4, projectId, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight4), roadwayId + 3, linearLocationId + 3, 8L, reversed = false, None, 86400L, roadwayNumber = nextRwNumber)
//      val projectLinkRight5 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 15L, 10L, 15L, None, None, None, 12353L, 0.0, 15.2, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferRight5, projectId, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight5), roadwayId + 4, linearLocationId + 4, 8L, reversed = false, None, 86400L, roadwayNumber = nextRwNumber)
//
//      //create before transfer data
//      val (linearLeft1, rwLeft1): (LinearLocation, Roadway) = Seq(projectLinkLeft1).map(toRoadwayAndLinearLocation).head
//      val (linearLeft2, rwLeft2): (LinearLocation, Roadway) = Seq(projectLinkLeft2).map(toRoadwayAndLinearLocation).head
//      val rw1WithId = rwLeft1.copy(id = roadwayId, ely = 8L)
//      val rw2WithId = rwLeft2.copy(id = roadwayId+1, ely = 8L)
//      val linearLeft1WithId = linearLeft1.copy(id = linearLocationId)
//      val linearLeft2WithId = linearLeft2.copy(id = linearLocationId+1)
//
//      //create after transfer data
//      val (linearRight3, rwRight3): (LinearLocation, Roadway) = Seq(projectLinkRight3).map(toRoadwayAndLinearLocation).head
//      val (linearRight4, rwRight4): (LinearLocation, Roadway) = Seq(projectLinkRight4).map(toRoadwayAndLinearLocation).head
//      val (linearRight5, rwRight5): (LinearLocation, Roadway) = Seq(projectLinkRight5).map(toRoadwayAndLinearLocation).head
//      val rw3WithId = rwRight3.copy(id = roadwayId+2, ely = 8L)
//      val rw4WithId = rwRight4.copy(id = roadwayId+3, ely = 8L)
//      val rw5WithId = rwRight5.copy(id = roadwayId+4, ely = 8L)
//      val linearRight3WithId = linearRight3.copy(id = linearLocationId+2)
//      val linearRight4WithId = linearRight4.copy(id = linearLocationId+3)
//      val linearRight5WithId = linearRight5.copy(id = linearLocationId+4)
//
//      buildTestDataForProject(Some(project), Some(Seq(rw1WithId, rw2WithId, rw3WithId, rw4WithId, rw5WithId)), Some(Seq(linearLeft1WithId, linearLeft2WithId, linearRight3WithId, linearRight4WithId, linearRight5WithId)), None)
//
//      /*  assignMValues before roundabout */
//      val assignedValues = defaultSectionCalculatorStrategy.assignMValues(Seq(projectLinkRight1, projectLinkRight2), Seq(projectLinkLeft1, projectLinkLeft2), Seq.empty[UserDefinedCalibrationPoint])
//
//      val (left, right) = assignedValues.partition(_.track == Track.LeftSide)
//      val groupedLeft1: ListMap[Long, Seq[ProjectLink]] = ListMap(left.groupBy(_.roadwayNumber).toSeq.sortBy(r => r._2.minBy(_.startAddrMValue).startAddrMValue): _*)
//      val groupedRight1: ListMap[Long, Seq[ProjectLink]] = ListMap(right.groupBy(_.roadwayNumber).toSeq.sortBy(r => r._2.minBy(_.startAddrMValue).startAddrMValue): _*)
//      groupedLeft1.size should be (groupedRight1.size)
//      groupedLeft1.size should be (1)
//      groupedLeft1.zip(groupedRight1).forall(zipped => zipped._1._2.maxBy(_.endAddrMValue).endAddrMValue == zipped._2._2.maxBy(_.endAddrMValue).endAddrMValue) should be (true)
//
//      val assignedValues2 = defaultSectionCalculatorStrategy.assignMValues(Seq(projectLinkRight1, projectLinkRight2.copy(administrativeClass = AdministrativeClass.Private)), Seq(projectLinkLeft1, projectLinkLeft2.copy(administrativeClass = AdministrativeClass.Private, roadwayNumber = Sequences.nextRoadwayNumber)), Seq.empty[UserDefinedCalibrationPoint])
//
//      val (left2, right2) = assignedValues2.partition(_.track == Track.LeftSide)
//      //should have same 2 different roadwayNumber since they have 2 different administrativeClasses (projectLinkLeft2 have now Private AdministrativeClass)
//      val groupedLeft2: ListMap[Long, Seq[ProjectLink]] = ListMap(left2.groupBy(_.roadwayNumber).toSeq.sortBy(r => r._2.minBy(_.startAddrMValue).startAddrMValue): _*)
//      val groupedRight2: ListMap[Long, Seq[ProjectLink]] = ListMap(right2.groupBy(_.roadwayNumber).toSeq.sortBy(r => r._2.minBy(_.startAddrMValue).startAddrMValue): _*)
//      groupedLeft2.size should be (groupedRight2.size)
//      groupedLeft2.size should be (2)
//      groupedLeft2.zip(groupedRight2).forall(zipped => zipped._1._2.maxBy(_.endAddrMValue).endAddrMValue == zipped._2._2.maxBy(_.endAddrMValue).endAddrMValue) should be (true)
//
//      /*  assignMValues before and after roundabout */
//      val assignedValues3 = defaultSectionCalculatorStrategy.assignMValues(Seq(projectLinkLeft3, projectLinkLeft4, projectLinkLeft5), assignedValues++Seq(projectLinkRight3, projectLinkRight4, projectLinkRight5), Seq.empty[UserDefinedCalibrationPoint])
//
//      val (left3, right3) = assignedValues3.partition(_.track == Track.LeftSide)
//      val groupedLeft3: ListMap[Long, Seq[ProjectLink]] = ListMap(left3.groupBy(_.roadwayNumber).toSeq.sortBy(r => r._2.minBy(_.startAddrMValue).startAddrMValue): _*)
//      val groupedRight3: ListMap[Long, Seq[ProjectLink]] = ListMap(right3.groupBy(_.roadwayNumber).toSeq.sortBy(r => r._2.minBy(_.startAddrMValue).startAddrMValue): _*)
//      groupedLeft3.size should be (groupedRight3.size)
//      groupedLeft3.size should be (3)
//      //groupedLeft3.zip(groupedRight3).forall(zipped => zipped._1._2.maxBy(_.endAddrMValue).endAddrMValue == zipped._2._2.maxBy(_.endAddrMValue).endAddrMValue) should be (true)
//
//      assignedValues3.find(_.linearLocationId == projectLinkRight4.linearLocationId).get.roadwayNumber should be (assignedValues3.find(_.linearLocationId == projectLinkRight5.linearLocationId).get.roadwayNumber)
//      assignedValues3.find(_.linearLocationId == projectLinkLeft4.linearLocationId).get.roadwayNumber should be (assignedValues3.find(_.linearLocationId == projectLinkLeft5.linearLocationId).get.roadwayNumber)
//
//      /*  assignMValues after roundabout */
//      val assignedValues4 = defaultSectionCalculatorStrategy.assignMValues(Seq(projectLinkLeft3, projectLinkLeft4, projectLinkLeft5), Seq(projectLinkRight3, projectLinkRight4, projectLinkRight5), Seq.empty[UserDefinedCalibrationPoint])
//
//      val (left4, right4) = assignedValues4.partition(_.track == Track.LeftSide)
//      left4.map(_.roadwayNumber).distinct.size should be (2)
//      right4.map(_.roadwayNumber).distinct.size should be (left4.map(_.roadwayNumber).distinct.size)
//      val groupedLeft4: ListMap[Long, Seq[ProjectLink]] = ListMap(left4.groupBy(_.roadwayNumber).toSeq.sortBy(r => r._2.minBy(_.startAddrMValue).startAddrMValue): _*)
//      val groupedRight4: ListMap[Long, Seq[ProjectLink]] = ListMap(right4.groupBy(_.roadwayNumber).toSeq.sortBy(r => r._2.minBy(_.startAddrMValue).startAddrMValue): _*)
//      groupedLeft4.size should be (groupedRight4.size)
//      groupedLeft4.size should be (2)
//      groupedLeft4.zip(groupedRight4).forall(zipped => zipped._1._2.maxBy(_.endAddrMValue).endAddrMValue == zipped._2._2.maxBy(_.endAddrMValue).endAddrMValue) should be (true)
//    }
//  }
  /* Unnecessary roadwaynumber checkings? VIITE-2348, DefaultSectionCalculatorStrategy.scala: 273, adjustableToRoadwayNumberAttribution. */
//  test("Test defaultSectionCalculatorStrategy.assignMValues() and the attribution of roadway_numbers for new Left Right sections that have already roadwayNumbers Then " +
//    "if there are for e.g. 3 (three) consecutive links with different roadway_numbers (and all Transfer status), the first 3 (three) opposite track links  (with all New status and already splited) should generate also 3 new roadway_numbers") {
//    runWithRollback {
//      //  Left: Before roundabout (Transfer)
//      val geomTransferLeft1 = Seq(Point(0.0, 5.0), Point(5.0, 5.0))
//      val geomTransferLeft2 = Seq(Point(5.0, 5.0), Point(10.0, 5.0))
//      //  Left: After Roundabout (New)
//      val geomNewLeft3 = Seq(Point(20.0, 5.0), Point(21.0, 10.0))
//
//      //  Right: Before Roundabout (New)
//      val geomNewRight1 = Seq(Point(0.0, 0.0), Point(5.0, 0.0))
//      val geomNewRight2 = Seq(Point(5.0, 0.0), Point(10.0, 0.0))
//      //  Right: After roundabout (Transfer)
//      val geomTransferRight3 = Seq(Point(20.0, 0.0), Point(19.0, 5.0))
//
//
//      val projectId = Sequences.nextViiteProjectId
//      val roadwayId = Sequences.nextRoadwayId
//      val linearLocationId = Sequences.nextLinearLocationId
//      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
//        "", Seq(), Seq(), None, None)
//
//      // Project Links:
//
//      //  Left: Before roundabout (Transfer)
//      val projectLinkLeft1 = ProjectLink(Sequences.nextProjectLinkId, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 5L, 0L, 5L, None, None, None, 12345L, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferLeft1, projectId, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferLeft1), roadwayId, linearLocationId, 8L, reversed = false, None, 86400L, roadwayNumber = Sequences.nextRoadwayNumber)
//      val projectLinkLeft2 = ProjectLink(Sequences.nextProjectLinkId, 9999L, 1L, Track.apply(2), Discontinuity.ParallelLink, 5L, 10L, 5L, 10L, None, None, None, 12346L, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferLeft2, projectId, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferLeft2), roadwayId + 1, linearLocationId + 1, 8L, reversed = false, None, 86400L, roadwayNumber = Sequences.nextRoadwayNumber)
//
//      //  Create before Transfer data
//      val (linearLeft1, rwLeft1): (LinearLocation, Roadway) = Seq(projectLinkLeft1).map(toRoadwayAndLinearLocation).head
//      val (linearLeft2, rwLeft2): (LinearLocation, Roadway) = Seq(projectLinkLeft2).map(toRoadwayAndLinearLocation).head
//      val rw1WithId = rwLeft1.copy(id = roadwayId, ely = 8L)
//      val rw2WithId = rwLeft2.copy(id = roadwayId+1, ely = 8L)
//      val linearLeft1WithId = linearLeft1.copy(id = linearLocationId)
//      val linearLeft2WithId = linearLeft2.copy(id = linearLocationId+1)
//
//      //  Right: Before Roundabout (New)
//      val projectLinkRight1 = ProjectLink(Sequences.nextProjectLinkId, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 5L, 0L, 0L, None, None, None, 12347L, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNewRight1, projectId, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewRight1), 0, 0, 8L, reversed = false, None, 86400L)
//      val projectLinkRight2 = ProjectLink(Sequences.nextProjectLinkId, 9999L, 1L, Track.apply(1), Discontinuity.MinorDiscontinuity, 5L, 10L, 0L, 0L, None, None, None, 12348L, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNewRight2, projectId, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewRight2), 0, 0, 8L, reversed = false, None, 86400L)
//
//      //  Left: After Roundabout (New)
//      val projectLinkLeft3 = ProjectLink(Sequences.nextProjectLinkId, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 10L, 15L, 0L, 0L, None, None, None, 12349L, 0.0, 5.1, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNewLeft3, projectId, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewLeft3), 0, 0, 8L, reversed = false, None, 86400L)
//      //  Right: After roundabout (Transfer)
//      val projectLinkRight3 = ProjectLink(Sequences.nextProjectLinkId, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 10L, 15L, 0L, 5L, None, None, None, 12352L, 0.0, 5.1, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferRight3, projectId, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight3), roadwayId + 2, linearLocationId + 2, 8L, reversed = false, None, 86400L, roadwayNumber = Sequences.nextRoadwayNumber)
//
//      //  Create after Transfer Data
//      val (linearRight3, rwRight3): (LinearLocation, Roadway) = Seq(projectLinkRight3).map(toRoadwayAndLinearLocation).head
//      val rw3WithId = rwRight3.copy(id = roadwayId+2, ely = 8L)
//      val linearRight3WithId = linearRight3.copy(id = linearLocationId+2)
//
//      buildTestDataForProject(Some(project), Some(Seq(rw1WithId, rw2WithId, rw3WithId)), Some(Seq(linearLeft1WithId, linearLeft2WithId, linearRight3WithId)), None)
//
//      //  Assign m values before roundabout
//      val assignedValues = defaultSectionCalculatorStrategy.assignMValues(Seq(projectLinkRight1, projectLinkRight2, projectLinkLeft3), Seq(projectLinkLeft1, projectLinkLeft2, projectLinkRight3), Seq.empty[UserDefinedCalibrationPoint])
//
//      val reAssignedRight1 = assignedValues.filter(_.id == projectLinkRight1.id).head
//      val reAssignedRight2 = assignedValues.filter(_.id == projectLinkRight2.id).head
//
//      projectLinkRight1.roadwayNumber should be (projectLinkRight2.roadwayNumber)
//      reAssignedRight1.roadwayNumber should not be projectLinkRight1.roadwayNumber
//      reAssignedRight2.roadwayNumber should not be reAssignedRight1.roadwayNumber
//    }
//  }

  /* This roadwaynumber based test needs fixing / rethinking */
/* This test caused a split to right side with calibration point copied to middle link. Test fails to  mismatch on last line. -> should remove old roadway splitting? */

  /*test("Test defaultSectionCalculatorStrategy.assignMValues() and the attribution of roadway_numbers for new Left Right sections with same number of links Then " +
    "if there are for e.g. 2 (two) consecutive links with diff roadway number (and all Transfer status), the opposite track (New), should also have 2 diff roadway numbers link(s) even if the amount of the new links is the same than the Transfer side") {
    runWithRollback {

      /*geoms
         ^  ^
         |  |
Left     |  ^   Right
         ^  |
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
      val geomNewLeft1 = Seq(Point(10.0, 10.0), Point(8.0, 13.0))
      val geomNewLeft2 = Seq(Point(8.0, 13.0), Point(5.0, 20.0))

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
      val projectLinkCombined1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 5L, 0L, 5L, None, None, None, 12345L, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferCombined1, projectId, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferCombined1), roadwayId1, linearLocationId1, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber1)

      val projectLinkCombined2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 5L, 10L, 5L, 10L, None, None, None, 12346L, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferCombined2, projectId, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferCombined2), roadwayId2, linearLocationId2, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber2)


      //Left New
      val projectLinkLeft1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12348L, 0.0, 3.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNewLeft1, projectId, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewLeft1), -1000, -1000, 8L, reversed = false, None, 86400L)
      val projectLinkLeft2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Discontinuous, 0L, 0L, 0L, 0L, None, None, None, 12349L, 0.0, 6.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNewLeft2, projectId, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewLeft2), -1000, -1000, 8L, reversed = false, None, 86400L)

      //Right Transfer
      val projectLinkRight1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 10L, 23L, 10L, 23L, None, None, None, 12346L, 5.0, 18.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferRight1, projectId, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight1), roadwayId2, linearLocationId3, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber2)
      val projectLinkRight2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Discontinuous, 23L, 30, 23L, 30L, None, None, None, 12347L, 0.0, 7.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferRight2, projectId, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight2), roadwayId3, linearLocationId4, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber3)

      //create before transfer data
      val roadwayCombined1 =  Roadway(roadwayId1, roadwayNumber1, 9999L, 1, AdministrativeClass.State, Track.apply(0), Discontinuity.Continuous, 0, 5, reversed = false, DateTime.now(), None, "tester", Some("rd 9999"), 8L, TerminationCode.NoTermination, DateTime.now(), None)
      val linearCombined1 = LinearLocation(linearLocationId1, 1, 12345L, 0.0, 5.0, SideCode.TowardsDigitizing, 86400L,
        (CalibrationPointsUtils.toCalibrationPointReference(Some(CalibrationPoint(12345L, 0.0, 0))),
          CalibrationPointsUtils.toCalibrationPointReference(None)),
        geomTransferCombined1, LinkGeomSource.NormalLinkInterface,
        roadwayNumber1, Some(DateTime.now().minusDays(1)), None)

      val roadwayCombined2 = Roadway(roadwayId2, roadwayNumber2, 9999L, 1, AdministrativeClass.State, Track.apply(0), Discontinuity.Continuous, 5, 18, reversed = false, DateTime.now(), None, "tester", Some("rd 9999"), 8L, TerminationCode.NoTermination, DateTime.now(), None)
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

      val roadwayCombined3 = Roadway(roadwayId3, roadwayNumber3, 9999L, 1, AdministrativeClass.State, Track.apply(0), Discontinuity.Discontinuous, 0, 7, reversed = false, DateTime.now(), None, "tester", Some("rd 9999"), 8L, TerminationCode.NoTermination, DateTime.now(), None)
      val linearCombined4 = LinearLocation(linearLocationId4, 1, 12347L, 0.0, 7.0, SideCode.TowardsDigitizing, 86400L,
        (CalibrationPointsUtils.toCalibrationPointReference(None),
          CalibrationPointsUtils.toCalibrationPointReference(Some(CalibrationPoint(12347L, 7.0, 30)))),
        geomTransferRight2, LinkGeomSource.NormalLinkInterface,
        roadwayNumber3, Some(DateTime.now().minusDays(1)), None)

      buildTestDataForProject(Some(project), Some(Seq(roadwayCombined1, roadwayCombined2, roadwayCombined3)), Some(Seq(linearCombined1, linearCombined2, linearCombined3, linearCombined4)), None)
      var assignedValues = defaultSectionCalculatorStrategy.assignMValues(Seq(projectLinkLeft1, projectLinkLeft2), Seq(projectLinkCombined1, projectLinkCombined2,
        projectLinkRight1, projectLinkRight2), Seq.empty[UserDefinedCalibrationPoint])
      assignedValues = assignedValues.filterNot(_.startAddrMValue == 14) :+  assignedValues.filter(_.startAddrMValue == 14).head.copy(discontinuity = Discontinuity.Continuous)

      val (left, right) = assignedValues.filterNot(_.track == Track.Combined).partition(_.track == Track.LeftSide)
      val groupedLeft: ListMap[Long, Seq[ProjectLink]] = ListMap(left.groupBy(_.roadwayNumber).toSeq.sortBy(r => r._2.minBy(_.startAddrMValue).startAddrMValue): _*)
      val groupedRight: ListMap[Long, Seq[ProjectLink]] = ListMap(right.groupBy(_.roadwayNumber).toSeq.sortBy(r => r._2.minBy(_.startAddrMValue).startAddrMValue): _*)
      groupedLeft.size should be (groupedRight.size)
      groupedLeft.size should be (2)
      groupedLeft.zip(groupedRight).forall(zipped => zipped._1._2.maxBy(_.endAddrMValue).endAddrMValue == zipped._2._2.maxBy(_.endAddrMValue).endAddrMValue) should be (true)
    }
  }*/

    /* This roadwaynumber based test needs fixing / rethinking  */
  /*test("Test defaultSectionCalculatorStrategy.assignMValues() and the attribution of roadway_numbers for new Left Right sections with diff number of links Then " +
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
          ^
          |
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
      val projectLinkCombined1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 5L, 0L, 5L, None, None, None, 12345L, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferCombined1, projectId, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferCombined1), roadwayId1, linearLocationId1, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber1)

      val projectLinkCombined2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 5L, 10L, 5L, 10L, None, None, None, 12346L, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferCombined2, projectId, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferCombined2), roadwayId2, linearLocationId2, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber2)


      //Left New
      val projectLinkLeft1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12348L, 0.0, 3.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNewLeft1, projectId, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewLeft1), -1000, -1000, 8L, reversed = false, None, 86400L)
      val projectLinkLeft2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12349L, 0.0, 6.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNewLeft2, projectId, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewLeft2), -1000, -1000, 8L, reversed = false, None, 86400L)
      val projectLinkLeft3 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Discontinuous, 0L, 0L, 0L, 0L, None, None, None, 12350L, 0.0, 10.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNewLeft3, projectId, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewLeft3), -1000, -1000, 8L, reversed = false, None, 86400L)

      //Right Transfer
      val projectLinkRight1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 10L, 23L, 10L, 23L, None, None, None, 12346L, 5.0, 18.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferRight1, projectId, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight1), roadwayId2, linearLocationId3, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber2)
      val projectLinkRight2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Discontinuous, 23L, 30, 23L, 30L, None, None, None, 12347L, 0.0, 7.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferRight2, projectId, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight2), roadwayId3, linearLocationId4, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber3)

      //create before transfer data
      val roadwayCombined1 =  Roadway(roadwayId1, roadwayNumber1, 9999L, 1, AdministrativeClass.State, Track.apply(0), Discontinuity.Continuous, 0, 5, reversed = false, DateTime.now(), None, "tester", Some("rd 9999"), 8L, TerminationCode.NoTermination, DateTime.now(), None)
      val linearCombined1 = LinearLocation(linearLocationId1, 1, 12345L, 0.0, 5.0, SideCode.TowardsDigitizing, 86400L,
        (CalibrationPointsUtils.toCalibrationPointReference(Some(CalibrationPoint(12345L, 0.0, 0))),
          CalibrationPointsUtils.toCalibrationPointReference(None)),
        geomTransferCombined1, LinkGeomSource.NormalLinkInterface,
        roadwayNumber1, Some(DateTime.now().minusDays(1)), None)

      val roadwayCombined2 = Roadway(roadwayId2, roadwayNumber2, 9999L, 1, AdministrativeClass.State, Track.apply(0), Discontinuity.Continuous, 5, 18, reversed = false, DateTime.now(), None, "tester", Some("rd 9999"), 8L, TerminationCode.NoTermination, DateTime.now(), None)
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

      val roadwayCombined3 = Roadway(roadwayId3, roadwayNumber3, 9999L, 1, AdministrativeClass.State, Track.apply(0), Discontinuity.Discontinuous, 0, 7, reversed = false, DateTime.now(), None, "tester", Some("rd 9999"), 8L, TerminationCode.NoTermination, DateTime.now(), None)
      val linearCombined4 = LinearLocation(linearLocationId4, 1, 12347L, 0.0, 7.0, SideCode.TowardsDigitizing, 86400L,
        (CalibrationPointsUtils.toCalibrationPointReference(None),
          CalibrationPointsUtils.toCalibrationPointReference(Some(CalibrationPoint(12347L, 7.0, 30)))),
        geomTransferRight2, LinkGeomSource.NormalLinkInterface,
        roadwayNumber3, Some(DateTime.now().minusDays(1)), None)

      buildTestDataForProject(Some(project), Some(Seq(roadwayCombined1, roadwayCombined2, roadwayCombined3)), Some(Seq(linearCombined1, linearCombined2, linearCombined3, linearCombined4)), None)
      val assignedValues = defaultSectionCalculatorStrategy.assignMValues(Seq(projectLinkLeft1, projectLinkLeft2, projectLinkLeft3), Seq(projectLinkCombined1, projectLinkCombined2, projectLinkRight1, projectLinkRight2), Seq.empty[UserDefinedCalibrationPoint])

      val (left, right) = assignedValues.filterNot(_.track == Track.Combined).partition(_.track == Track.LeftSide)
      val groupedLeft: ListMap[Long, Seq[ProjectLink]] = ListMap(left.groupBy(_.roadwayNumber).toSeq.sortBy(r => r._2.minBy(_.startAddrMValue).startAddrMValue): _*)
      val groupedRight: ListMap[Long, Seq[ProjectLink]] = ListMap(right.groupBy(_.roadwayNumber).toSeq.sortBy(r => r._2.minBy(_.startAddrMValue).startAddrMValue): _*)
      groupedLeft.size should be (groupedRight.size)
      groupedLeft.size should be (2)
      groupedLeft.zip(groupedRight).forall(zipped => zipped._1._2.maxBy(_.endAddrMValue).endAddrMValue == zipped._2._2.maxBy(_.endAddrMValue).endAddrMValue) should be (true)
    }
  }*/

  /* This roadwaynumber based test needs fixing / rethinking  */
  /*test("Test defaultSectionCalculatorStrategy.assignMValues() and the attribution of roadway_numbers for new Left Right sections with diff number of links Then " +
    "if there are for e.g. 2 (two) consecutive links with diff roadway number (and all Transfer status), the opposite track (New), should split their link(s) if the amount of links is lower than two, to have the same amount of roadway numbers") {
    runWithRollback {
      /*  Geoms

              ^   ^
              |   |
         Left |   ^  Right
              |   |
              |   |
               \ /
                ^
                |
                ^
                |
        */

      //  Combined
      val geomTransferCombined1 = Seq(Point(10.0, 0.0), Point(10.0, 5.0))
      val geomTransferCombined2 = Seq(Point(10.0, 5.0), Point(10.0, 10.0))

      //  Left
      val geomNewLeft1 = Seq(Point(10.0, 10.0), Point(5.0, 20.0))

      //  Right
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

      //  Combined Transfer
      val projectLinkCombined1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 5L, 0L, 5L, None, None, None, 12345L, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferCombined1, projectId, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferCombined1), roadwayId1, linearLocationId1, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber1)

      val projectLinkCombined2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 5L, 10L, 5L, 10L, None, None, None, 12346L, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferCombined2, projectId, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferCombined2), roadwayId2, linearLocationId2, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber2)

      //  Left New
      val projectLinkLeft1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Discontinuous, 0L, 0L, 0L, 0L, None, None, None, 12348L, 0.0, 11.18, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNewLeft1, projectId, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewLeft1), -1000, -1000, 8L, reversed = false, None, 86400L)

      //  Right Transfer
      val projectLinkRight1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 10L, 23L, 10L, 23L, None, None, None, 12346L, 5.0, 18.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferRight1, projectId, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight1), roadwayId2, linearLocationId3, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber2)
      val projectLinkRight2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Discontinuous, 23L, 30, 23L, 30L, None, None, None, 12347L, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferRight2, projectId, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight2), roadwayId3, linearLocationId4, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber3)

      //  Create before transfer data
      val roadwayCombined1 =  Roadway(roadwayId1, roadwayNumber1, 9999L, 1, AdministrativeClass.State, Track.apply(0), Discontinuity.Continuous, 0, 5, reversed = false, DateTime.now(), None, "tester", Some("rd 9999"), 8L, TerminationCode.NoTermination, DateTime.now(), None)
      val linearCombined1 = LinearLocation(linearLocationId1, 1, 12345L, 0.0, 5.0, SideCode.TowardsDigitizing, 86400L,
        (CalibrationPointsUtils.toCalibrationPointReference(Some(CalibrationPoint(12345L, 0.0, 0))),
          CalibrationPointsUtils.toCalibrationPointReference(None)),
        geomTransferCombined1, LinkGeomSource.NormalLinkInterface,
        roadwayNumber1, Some(DateTime.now().minusDays(1)), None)

      val roadwayCombined2 = Roadway(roadwayId2, roadwayNumber2, 9999L, 1, AdministrativeClass.State, Track.apply(0), Discontinuity.Continuous, 5, 18, reversed = false, DateTime.now(), None, "tester", Some("rd 9999"), 8L, TerminationCode.NoTermination, DateTime.now(), None)
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

      val roadwayCombined3 = Roadway(roadwayId3, roadwayNumber3, 9999L, 1, AdministrativeClass.State, Track.apply(0), Discontinuity.Discontinuous, 0, 7, reversed = false, DateTime.now(), None, "tester", Some("rd 9999"), 8L, TerminationCode.NoTermination, DateTime.now(), None)
      val linearCombined4 = LinearLocation(linearLocationId4, 1, 12347L, 0.0, 7.0, SideCode.TowardsDigitizing, 86400L,
        (CalibrationPointsUtils.toCalibrationPointReference(None),
          CalibrationPointsUtils.toCalibrationPointReference(Some(CalibrationPoint(12347L, 7.0, 30)))),
        geomTransferRight2, LinkGeomSource.NormalLinkInterface,
        roadwayNumber3, Some(DateTime.now().minusDays(1)), None)

      buildTestDataForProject(Some(project), Some(Seq(roadwayCombined1, roadwayCombined2, roadwayCombined3)), Some(Seq(linearCombined1, linearCombined2, linearCombined3, linearCombined4)), None)
      val assignedValues = defaultSectionCalculatorStrategy.assignMValues(Seq(projectLinkLeft1), Seq(projectLinkCombined1, projectLinkCombined2, projectLinkRight1, projectLinkRight2), Seq.empty[UserDefinedCalibrationPoint])

      val (left, right) = assignedValues.filterNot(_.track == Track.Combined).partition(_.track == Track.LeftSide)
      val groupedLeft: ListMap[Long, Seq[ProjectLink]] = ListMap(left.groupBy(_.roadwayNumber).toSeq.sortBy(r => r._2.minBy(_.startAddrMValue).startAddrMValue): _*)
      val groupedRight: ListMap[Long, Seq[ProjectLink]] = ListMap(right.groupBy(_.roadwayNumber).toSeq.sortBy(r => r._2.minBy(_.startAddrMValue).startAddrMValue): _*)
      groupedLeft.size should be (groupedRight.size)
      groupedLeft.size should be (2)
      groupedLeft.zip(groupedRight).forall(zipped => zipped._1._2.maxBy(_.endAddrMValue).endAddrMValue == zipped._2._2.maxBy(_.endAddrMValue).endAddrMValue) should be (true)
    }
  }*/

  /* This roadwaynumber based test needs fixing / rethinking  */
  /*test("Test defaultSectionCalculatorStrategy.assignMValues() and the attribution of roadway_numbers for new Left Right sections with diff number of links Then " +
        "if there are for e.g. 3 (three) consecutive links with diff roadway number (and all Transfer status), the opposite track (New), should split their link(s) if the amount of links is lower than three, to have the same amount of roadway numbers, and not split the first one if the first transfer link found is too long when comparing to the opposite first New link") {
    runWithRollback {
      /*  Geometries

                 ^
                 |  Combined
                ^ ^ (30)
                |   \
                |     ----
                |         ^ (24)
          Left  |         | Right
           (xx) ^         |
                |         ^ (23)
                |        /
                |   ----
                | /
                 ^ (10)
                 |  Combined
                 |

        */

      //  Combined
      val geomTransferCombined1 = Seq(Point(10.0, 0.0), Point(10.0, 5.0))
      val geomTransferCombined2 = Seq(Point(10.0, 5.0), Point(10.0, 10.0))

      //  Left
      val geomNewLeft1 = Seq(Point(10.0, 10.0), Point(9.0, 11.0))
      val geomNewLeft2 = Seq(Point(9.0, 11.0), Point(5.0, 20.0))

      //  Right
      val geomTransferRight1 = Seq(Point(10.0, 10.0), Point(15.0, 23.0))
      val geomTransferRight2 = Seq(Point(15.0, 23.0), Point(15.0, 24.0))
      val geomTransferRight3 = Seq(Point(15.0, 24.0), Point(15.0, 30.0))


      val projectId = Sequences.nextViiteProjectId
      val roadwayId1 = Sequences.nextRoadwayId
      val roadwayId2 = Sequences.nextRoadwayId
      val roadwayId3 = Sequences.nextRoadwayId
      val roadwayNumber1 = Sequences.nextRoadwayNumber
      val roadwayNumber2 = Sequences.nextRoadwayNumber
      val roadwayNumber3 = Sequences.nextRoadwayNumber
      val roadwayNumber4 = Sequences.nextRoadwayNumber
      val linearLocationId1 = Sequences.nextLinearLocationId
      val linearLocationId2 = Sequences.nextLinearLocationId
      val linearLocationId3 = Sequences.nextLinearLocationId
      val linearLocationId4 = Sequences.nextLinearLocationId
      val linearLocationId5 = Sequences.nextLinearLocationId
      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      //  Project Links

      //  Combined Transfer
      val projectLinkCombined1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 5L, 0L, 5L, None, None, None, 12345L, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferCombined1, projectId, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferCombined1), roadwayId1, linearLocationId1, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber1)

      val projectLinkCombined2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 5L, 10L, 5L, 10L, None, None, None, 12346L, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferCombined2, projectId, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferCombined2), roadwayId2, linearLocationId2, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber2)


      //  Left New
      val projectLinkLeft1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12350L, 0.0, 1.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNewLeft1, projectId, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewLeft1), -1000, -1000, 8L, reversed = false, None, 86400L)
      val projectLinkLeft2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12348L, 0.0, 10.18, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNewLeft2, projectId, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewLeft2), -1000, -1000, 8L, reversed = false, None, 86400L)

      //  Right Transfer
      val projectLinkRight1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 10L, 23L, 10L, 23L, None, None, None, 12346L, 5.0, 18.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferRight1, projectId, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight1), roadwayId2, linearLocationId3, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber2)
      val projectLinkRight2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 23L, 24L, 23L, 24L, None, None, None, 12349L, 0.0, 1.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferRight2, projectId, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight2), roadwayId3, linearLocationId4, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber3)
      val projectLinkRight3 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Discontinuous, 24L, 30, 23L, 30L, None, None, None, 12347L, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferRight3, projectId, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight3), roadwayId3, linearLocationId5, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber4)

      //  Create before transfer data
      val roadwayCombined1 =  Roadway(roadwayId1, roadwayNumber1, 9999L, 1, AdministrativeClass.State, Track.apply(0), Discontinuity.Continuous, 0, 5, reversed = false, DateTime.now(), None, "tester", Some("rd 9999"), 8L, TerminationCode.NoTermination, DateTime.now(), None)
      val linearCombined1 = LinearLocation(linearLocationId1, 1, 12345L, 0.0, 5.0, SideCode.TowardsDigitizing, 86400L,
        (CalibrationPointsUtils.toCalibrationPointReference(Some(CalibrationPoint(12345L, 0.0, 0))),
          CalibrationPointsUtils.toCalibrationPointReference(None)),
        geomTransferCombined1, LinkGeomSource.NormalLinkInterface,
        roadwayNumber1, Some(DateTime.now().minusDays(1)), None)

      val roadwayCombined2 = Roadway(roadwayId2, roadwayNumber2, 9999L, 1, AdministrativeClass.State, Track.apply(0), Discontinuity.Continuous, 5, 18, reversed = false, DateTime.now(), None, "tester", Some("rd 9999"), 8L, TerminationCode.NoTermination, DateTime.now(), None)
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

      val roadwayCombined3 = Roadway(roadwayId3, roadwayNumber3, 9999L, 1, AdministrativeClass.State, Track.apply(0), Discontinuity.Discontinuous, 0, 7, reversed = false, DateTime.now(), None, "tester", Some("rd 9999"), 8L, TerminationCode.NoTermination, DateTime.now(), None)
      val linearCombined4 = LinearLocation(linearLocationId4, 1, 12347L, 0.0, 7.0, SideCode.TowardsDigitizing, 86400L,
        (CalibrationPointsUtils.toCalibrationPointReference(None),
          CalibrationPointsUtils.toCalibrationPointReference(Some(CalibrationPoint(12347L, 7.0, 30)))),
        geomTransferRight2, LinkGeomSource.NormalLinkInterface,
        roadwayNumber3, Some(DateTime.now().minusDays(1)), None)

      buildTestDataForProject(Some(project), Some(Seq(roadwayCombined1, roadwayCombined2, roadwayCombined3)), Some(Seq(linearCombined1, linearCombined2, linearCombined3, linearCombined4)), None)
      val assignedValues = defaultSectionCalculatorStrategy.assignMValues(Seq(projectLinkLeft1, projectLinkLeft2), Seq(projectLinkCombined1, projectLinkCombined2, projectLinkRight1, projectLinkRight2, projectLinkRight3), Seq.empty[UserDefinedCalibrationPoint])

      val (left, right) = assignedValues.filterNot(_.track == Track.Combined).partition(_.track == Track.LeftSide)
      val groupedLeft: ListMap[Long, Seq[ProjectLink]] = ListMap(left.groupBy(_.roadwayNumber).toSeq.sortBy(r => r._2.minBy(_.startAddrMValue).startAddrMValue): _*)
      val groupedRight: ListMap[Long, Seq[ProjectLink]] = ListMap(right.groupBy(_.roadwayNumber).toSeq.sortBy(r => r._2.minBy(_.startAddrMValue).startAddrMValue): _*)
      groupedLeft.size should be (groupedRight.size)
      groupedLeft.size should be (3)
      groupedLeft.zip(groupedRight).forall(zipped => zipped._1._2.maxBy(_.endAddrMValue).endAddrMValue == zipped._2._2.maxBy(_.endAddrMValue).endAddrMValue) should be (true)
    }
  }*/

  /* This roadwaynumber based test needs fixing / rethinking  */
  /*test("Test defaultSectionCalculatorStrategy.assignMValues() and the attribution of roadway_numbers for new Left Right sections with diff number of links Then " +
    "if there are some consecutive links with diff roadway_number (and all Transfer status), the opposite track, should split their link(s) if the amount of links is lower than the Transfer track side with same end addresses as the other side and different roadway numbers") {
    runWithRollback {
      /*  Geometries

                    (25) ^
                         |  Combined2
                         |
                        ^ ^ (20)
                Left3 /   |  Right4
                 ----     |
           (13) ^         ^ (16)
                |         |
                |         |  Right3
          Left2 |         |
                |         ^ (13)
                |         |
                |         |  Right2
           (09) ^         |
                 \        ^ (10)
                   ----   |  Right1
                Left1   \ |
                         ^ (5)
                         |
                         | Combined1
                         |

        */

      //  Combined
      val geomTransferCombined1 = Seq(Point(10.0, 0.0), Point(10.0, 5.0))
      val geomTransferCombined2 = Seq(Point(12.0, 20.0), Point(12.0, 25.0))

      //  Left
      val geomNewLeft1 = Seq(Point(10.0, 5.0), Point(8.0, 10.0), Point(5.0, 10.0))
      val geomNewLeft2 = Seq(Point(5.0, 10.0), Point(0.0, 10.0), Point(0.0, 15.0))
      val geomNewLeft3 = Seq(Point(0.0, 15.0), Point(0.0, 20.0), Point(12.0, 20.0))

      //  Right
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

      //  Project Links

      //  Combined Transfer
      val projectLinkCombined1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 5L, 0L, 5L, None, None, None, 12345L, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferCombined1, projectId, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferCombined1), roadwayId1, linearLocationId1, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber1)

      val projectLinkCombined2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.EndOfRoad, 20L, 25L, 20L, 25L, None, None, None, 12349L, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferCombined2, projectId, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferCombined2), roadwayId2, linearLocationId6, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber2)

      //  Left New
      val projectLinkLeft1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12350L, 0.0, 20.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNewLeft1, projectId, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewLeft1), -1000, -1000, 8L, reversed = false, None, 86400L)
      val projectLinkLeft2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12351L, 0.0, 20.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNewLeft2, projectId, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewLeft2), -1000, -1000, 8L, reversed = false, None, 86400L)
      val projectLinkLeft3 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12352L, 0.0, 20.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNewLeft3, projectId, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewLeft3), -1000, -1000, 8L, reversed = false, None, 86400L)

      //  Right Transfer
      val projectLinkRight1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 5L, 10L, 5L, 10L, None, None, None, 12346L, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferRight1, projectId, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight1), roadwayId1, linearLocationId2, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber1)
      val projectLinkRight2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 10L, 13, 10L, 13L, None, None, None, 12347L, 3.0, 6.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferRight2, projectId, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight2), roadwayId1, linearLocationId3, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber1)
      val projectLinkRight3 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 13L, 16L, 13L, 16L, None, None, None, 12347L, 0.0, 3.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferRight3, projectId, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight3), roadwayId2, linearLocationId4, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber2)
      val projectLinkRight4 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 16L, 20L, 16L, 20L, None, None, None, 12348L, 0.0, 4.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferRight4, projectId, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight4), roadwayId2, linearLocationId5, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber2)

      //  Create before transfer data
      val roadwayCombined1 =  Roadway(roadwayId1, roadwayNumber1, 9999L, 1, AdministrativeClass.State, Track.apply(0), Discontinuity.Continuous, 0, 13, reversed = false, DateTime.now(), None, "tester", Some("rd 9999"), 8L, TerminationCode.NoTermination, DateTime.now(), None)
      val linearCombined1 = LinearLocation(linearLocationId1, 1, 12345L, 0.0, 5.0, SideCode.TowardsDigitizing, 86400L,
        (CalibrationPointsUtils.toCalibrationPointReference(Some(CalibrationPoint(12345L, 0.0, 0))),
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

      val roadwayCombined2 = Roadway(roadwayId2, roadwayNumber2, 9999L, 1, AdministrativeClass.State, Track.apply(0), Discontinuity.EndOfRoad, 13, 25, reversed = false, DateTime.now(), None, "tester", Some("rd 9999"), 8L, TerminationCode.NoTermination, DateTime.now(), None)
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
          CalibrationPointsUtils.toCalibrationPointReference(Some(CalibrationPoint(12349L, 5.0, 20)))),
        geomTransferCombined2, LinkGeomSource.NormalLinkInterface,
        roadwayNumber2, Some(DateTime.now().minusDays(1)), None)

      buildTestDataForProject(Some(project), Some(Seq(roadwayCombined1, roadwayCombined2)), Some(Seq(linearCombined1, linearCombined2, linearCombined3, linearCombined4, linearCombined5, linearCombined6)), None)
      val assignedValues = defaultSectionCalculatorStrategy.assignMValues(Seq(projectLinkLeft1, projectLinkLeft2, projectLinkLeft3), Seq(projectLinkCombined1, projectLinkRight1, projectLinkRight2, projectLinkRight3, projectLinkRight4, projectLinkCombined2), Seq.empty[UserDefinedCalibrationPoint])

      val (left, right) = assignedValues.filterNot(_.track == Track.Combined).partition(_.track == Track.LeftSide)
      val groupedLeft: ListMap[Long, Seq[ProjectLink]] = ListMap(left.groupBy(_.roadwayNumber).toSeq.sortBy(r => r._2.minBy(_.startAddrMValue).startAddrMValue): _*)
      val groupedRight: ListMap[Long, Seq[ProjectLink]] = ListMap(right.groupBy(_.roadwayNumber).toSeq.sortBy(r => r._2.minBy(_.startAddrMValue).startAddrMValue): _*)
      groupedLeft.size should be (groupedRight.size)
      groupedLeft.size should be (2)
      groupedLeft.zip(groupedRight).forall(zipped => zipped._1._2.maxBy(_.endAddrMValue).endAddrMValue == zipped._2._2.maxBy(_.endAddrMValue).endAddrMValue) should be (true)
    }
  }*/

  test("Test findStartingPoints When adding two new left and right track links before new and existing Combined links Then the starting points for the left and right road should be points of Left and Right Tracks and not one from the completely opposite side (where the existing Combined link is)") {
    runWithRollback {
      val geomLeft1 = Seq(Point(0.0, 15.0), Point(5.0, 17.0))
      val geomRight1 = Seq(Point(0.0, 10.0), Point(5.0, 17.0))
      val geomNewComb1 = Seq(Point(10.0, 15.0), Point(5.0, 17.0))//against
      val geomTransferComb1 = Seq(Point(20.0, 5.0), Point(15.0, 10.0))//against
      val geomTransferComb2 = Seq(Point(25.0, 0.0), Point(20.0, 5.0))//against
      val otherPartGeomTransferComb1 = Seq(Point(35.0, 0.0), Point(30.0, 0.0))//against
      val plId = Sequences.nextProjectLinkId


      val projectLinkOtherPartComb1 = ProjectLink(plId + 1, 9999L, 2L, Track.Combined, Discontinuity.Continuous, 0L, 5L, 0L, 5L, None, None, None, 12344L.toString, 0.0, 5.0, SideCode.AgainstDigitizing, (NoCP, NoCP), (NoCP, NoCP), otherPartGeomTransferComb1, 0L, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(otherPartGeomTransferComb1), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkComb1 = ProjectLink(plId + 1, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 5L, 10L, 5L, 10L, None, None, None, 12345L.toString, 0.0, 5.0, SideCode.AgainstDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferComb1, 0L, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferComb1), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkComb2 = ProjectLink(plId + 2, 9999L, 1L, Track.Combined, Discontinuity.MinorDiscontinuity, 10L, 15L, 10L, 15L, None, None, None, 12346L.toString, 0.0, 5.0, SideCode.AgainstDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferComb2, 0L, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferComb2), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkCombNewBefore = ProjectLink(plId + 3, 9999L, 1L, Track.Combined, Discontinuity.MinorDiscontinuity, 0L, 5L, 0L, 5L, None, None, None, 12347L.toString, 0.0, 5.0, SideCode.AgainstDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNewComb1, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewComb1), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkNewLeft = ProjectLink(plId + 4, 9999L, 1L, Track.LeftSide, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12348L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomLeft1, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft1), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkNewRight = ProjectLink(plId + 5, 9999L, 1L, Track.RightSide, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12349L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomRight1, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight1), 0L, 0, 0, reversed = false, None, 86400L)


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


      val projectLinkNewComb1Before = ProjectLink(plId, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 0, 0, 0, None, None, None, 12344L.toString, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNewComb1, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewComb1), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkComb1 = ProjectLink(plId + 1, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 5L, 0L, 5L, None, None, None, 12345L.toString, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferComb1, 0L, LinkStatus.NotHandled, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferComb1), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkComb2 = ProjectLink(plId + 2, 9999L, 1L, Track.Combined, Discontinuity.EndOfRoad, 5L, 10L, 5L, 10L, None, None, None, 12346L.toString, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferComb2, 0L, LinkStatus.NotHandled, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferComb2), 0L, 0, 0, reversed = false, None, 86400L)

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


      val projectLinkNotHandledComb1Part1 = ProjectLink(plId, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 5L, 0L, 5L, None, None, None, 12344L.toString, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNotHandledComb1Part1, 0L, LinkStatus.NotHandled, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNotHandledComb1Part1), 0L, 0L, 0L, reversed = false, None, 86400L)
      val projectLinkNotHandledComb2Part1 = ProjectLink(plId + 1, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 5L, 10L, 5L, 10L, None, None, None, 12345L.toString, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNotHandledComb2Part1, 0L, LinkStatus.NotHandled, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNotHandledComb2Part1), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkTransferComb1Part2ToPart1 = ProjectLink(plId + 2, 9999L, 1L, Track.Combined, Discontinuity.EndOfRoad, 0L, 6L, 0L, 6L, None, None, None, 12346L.toString, 0.0, 6.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferComb1Part2ToPart1, 0L, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferComb1Part2ToPart1), 0L, 0, 0, reversed = false, None, 86400L)

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


      val projectLinkNotHandledComb1Part1 = ProjectLink(plId, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 5L, 0L, 5L, None, None, None, 12344L.toString, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNotHandledComb1Part1, 0L, LinkStatus.NotHandled, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNotHandledComb1Part1), 0L, 0L, 0L, reversed = false, None, 86400L)
      val projectLinkNotHandledComb2Part1 = ProjectLink(plId + 1, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 5L, 10L, 5L, 10L, None, None, None, 12345L.toString, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNotHandledComb2Part1, 0L, LinkStatus.NotHandled, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNotHandledComb2Part1), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkTransferComb1Part2ToPart1 = ProjectLink(plId + 2, 9999L, 1L, Track.Combined, Discontinuity.EndOfRoad, 0L, 6L, 0L, 6L, None, None, None, 12346L.toString, 0.0, 6.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferComb1Part2ToPart1, 0L, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferComb1Part2ToPart1), 0L, 0, 0, reversed = false, None, 86400L)

      val transferProjectLinks = Seq(projectLinkTransferComb1Part2ToPart1, projectLinkNotHandledComb1Part1, projectLinkNotHandledComb2Part1)
      val newProjectLinks = Seq()
      val otherPartLinks = Seq()

      val startingPointsForCalculations = defaultSectionCalculatorStrategy.findStartingPoints(newProjectLinks, transferProjectLinks, otherPartLinks, Seq.empty[UserDefinedCalibrationPoint])
      startingPointsForCalculations should be((projectLinkTransferComb1Part2ToPart1.startingPoint, projectLinkTransferComb1Part2ToPart1.startingPoint))
    }
  }

  test("Test defaultSectionCalculatorStrategy.findStartingPoint() When transferring last link of part 1 to part 2, that is before the old first part 2 link Then the starting point should be the one from new link of part2 that is being transferred and the direction of both parts should not change") {
    runWithRollback {
      val geomNotHandledPart1 = Seq(Point(0.0, 0.0), Point(5.0, 0.0))
      val geomTransferNewFirstLinkPart2 = Seq(Point(5.0, 0.0), Point(10.0, 0.0))
      val geomTransferOldFirstLinkPart2 = Seq(Point(10.0, 0.0), Point(15.0, 0.0))
      val plId = Sequences.nextProjectLinkId

      val projectLinkTransferNewFirstLinkPart2 = ProjectLink(plId + 1, 9999L, 2L, Track.Combined, Discontinuity.Continuous, 5L, 10L, 5L, 10L, None, None, None, 12345L.toString, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferNewFirstLinkPart2, 0L, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferNewFirstLinkPart2), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkTransferOldFirstLinkPart2 = ProjectLink(plId + 2, 9999L, 2L, Track.Combined, Discontinuity.EndOfRoad, 0L, 5L, 0L, 6L, None, None, None, 12346L.toString, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferOldFirstLinkPart2, 0L, LinkStatus.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferOldFirstLinkPart2), 0L, 0, 0, reversed = false, None, 86400L)

      val transferProjectLinks = Seq(projectLinkTransferNewFirstLinkPart2, projectLinkTransferOldFirstLinkPart2)
      val newProjectLinks = Seq()
      val otherPartLinks = Seq()

      val startingPointsForCalculations = defaultSectionCalculatorStrategy.findStartingPoints(newProjectLinks, transferProjectLinks, otherPartLinks, Seq.empty[UserDefinedCalibrationPoint])
      startingPointsForCalculations should be((projectLinkTransferNewFirstLinkPart2.startingPoint, projectLinkTransferNewFirstLinkPart2.startingPoint))
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

      val projectLinkNew1 = ProjectLink(plId, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 10L, 0L, 0L, None, None, None, 12344L.toString, 0.0, 10.0, SideCode.AgainstDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNew1, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew1), 0L, 0, 0, reversed = false, None, 86400L)

      val geomNew2 = Seq(Point(0.0, 10.0), Point(0.0, 20.0))

      val projectLinkNew2 = ProjectLink(plId + 1, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12347L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomNew2, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew2), 0L, 0, 0, reversed = false, None, 86400L)

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

      val projectLinkNew1 = ProjectLink(plId, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 10L, 0L, 0L, None, None, None, 12344L.toString, 0.0, 10.0, SideCode.AgainstDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNew1, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew1), 0L, 0, 0, reversed = false, None, 86400L)

      val geomNew2 = Seq(Point(0.0, 0.0), Point(0.0, 10.0))

      val projectLinkNew2 = ProjectLink(plId + 1, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12347L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomNew2, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew2), 0L, 0, 0, reversed = false, None, 86400L)

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

      val projectLinkNew1 = ProjectLink(plId, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12344L.toString, 0.0, 9.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomNew1, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew1), 0L, 0, 0, reversed = false, None, 86400L)

      val geomNew2 = Seq(Point(973.346,33.188,93.2029999999941),
        Point(847.231,62.266,94.823000000004),
        Point(792.515,54.912,95.8469999999943))

      val projectLinkNew2 = ProjectLink(plId + 1, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12345L.toString, 0.0, 10.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomNew2, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew2), 0L, 0, 0, reversed = false, None, 86400L)

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

      val projectLinkNew1 = ProjectLink(plId, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 10L, 0L, 0L, None, None, None, 12344L.toString, 0.0, 10.0, SideCode.AgainstDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNew1, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew1), 0L, 0, 0, reversed = false, None, 86400L)

      val geomNew2 = Seq(Point(0.0, 0.0), Point(5.0, 10.0))

      val projectLinkNew2 = ProjectLink(plId + 1, 9999L, 1L, Track.RightSide, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12345L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomNew2, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew2), 0L, 0, 0, reversed = false, None, 86400L)

      val geomNew3 = Seq(Point(10.0, 0.0), Point(5.0, 10.0))

      val projectLinkNew3 = ProjectLink(plId + 2, 9999L, 1L, Track.LeftSide, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12346L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomNew3, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew3), 0L, 0, 0, reversed = false, None, 86400L)

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

      val projectLinkNew1 = ProjectLink(plId, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 10L, 0L, 0L, None, None, None, 12344L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNew1, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew1), 0L, 0, 0, reversed = false, None, 86400L)

      val geomNew2 = Seq(Point(0.0, 0.0), Point(5.0, 10.0))

      val projectLinkNew2 = ProjectLink(plId + 1, 9999L, 1L, Track.LeftSide, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12345L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomNew2, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew2), 0L, 0, 0, reversed = false, None, 86400L)

      val geomNew3 = Seq(Point(10.0, 0.0), Point(5.0, 10.0))

      val projectLinkNew3 = ProjectLink(plId + 2, 9999L, 1L, Track.RightSide, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12346L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomNew3, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew3), 0L, 0, 0, reversed = false, None, 86400L)

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

      val projectLink1 = ProjectLink(1, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 10L, 0L, 0L, None, None, None, 12344L.toString, 0.0, 10.0, sideCode, (NoCP, NoCP), (NoCP, NoCP), geom1, 0L, statusOfExisting, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom1), 0L, 0, 0, reversed = false, None, 86400L)

      val geomNew2 = Seq(Point(0.0, 20.0), Point(0.0, 30.0))
      val geomNew3 = Seq(Point(0.0, 0.0), Point(0.0, 10.0))

      val projectLinkNew2 = ProjectLink(2, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12345L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomNew2, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew2), 0L, 0, 0, reversed = false, None, 86400L)

      val projectLinkNew3 = ProjectLink(3, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12346L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomNew3, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew3), 0L, 0, 0, reversed = false, None, 86400L)

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

      val projectLink0 = ProjectLink(0, 9999L, 1L, if (sideCode == TowardsDigitizing) Track.LeftSide else Track.RightSide, Discontinuity.Continuous, 0L, 10L, 0L, 0L, None, None, None, 12344L.toString, 0.0, GeometryUtils.geometryLength(geom0), sideCode, (NoCP, NoCP), (NoCP, NoCP), geom0, 0L, statusOfExisting, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom0), 0L, 0, 0, reversed = false, None, 86400L)

      val projectLink1 = ProjectLink(1, 9999L, 1L, if (sideCode == TowardsDigitizing) Track.RightSide else Track.LeftSide, Discontinuity.Continuous, 0L, 10L, 0L, 0L, None, None, None, 12344L.toString, 0.0, GeometryUtils.geometryLength(geom1), sideCode, (NoCP, NoCP), (NoCP, NoCP), geom1, 0L, statusOfExisting, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom1), 0L, 0, 0, reversed = false, None, 86400L)

      val geomNew2 = Seq(Point(10.0, 20.0), Point(10.0, 30.0))
      val geomNew3 = Seq(Point(10.0, 0.0), Point(10.0, 10.0))

      val projectLinkNew2 = ProjectLink(2, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12345L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomNew2, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew2), 0L, 0, 0, reversed = false, None, 86400L)

      val projectLinkNew3 = ProjectLink(3, 9999L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12346L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomNew3, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew3), 0L, 0, 0, reversed = false, None, 86400L)

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

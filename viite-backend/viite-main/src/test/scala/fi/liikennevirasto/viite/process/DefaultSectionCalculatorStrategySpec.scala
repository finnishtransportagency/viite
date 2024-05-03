package fi.liikennevirasto.viite.process

import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.process.strategy.DefaultSectionCalculatorStrategy
import fi.liikennevirasto.viite.util.CalibrationPointsUtils
import fi.liikennevirasto.viite.ProjectValidationException
import fi.vaylavirasto.viite.dao.Sequences
import fi.vaylavirasto.viite.geometry.{GeometryUtils, Point}
import fi.vaylavirasto.viite.model.CalibrationPointType.{JunctionPointCP, NoCP, RoadAddressCP}
import fi.vaylavirasto.viite.model.LinkGeomSource.{ComplementaryLinkInterface, FrozenLinkInterface, NormalLinkInterface}
import fi.vaylavirasto.viite.model.{AdministrativeClass, Discontinuity, LinkGeomSource, RoadAddressChangeType, RoadPart, SideCode, Track}
import fi.vaylavirasto.viite.postgis.PostGISDatabase.runWithRollback
import org.joda.time.DateTime
import org.scalatest.{FunSuite, Matchers}

class DefaultSectionCalculatorStrategySpec extends FunSuite with Matchers {
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

    val projectLink1 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 1L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom1, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom1), 0L, 0, 0, reversed = false, None, 86400L)
    val projectLink2 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 2L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom2, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom2), 0L, 0, 0, reversed = false, None, 86400L)
    val projectLink3 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 3L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom3, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom3), 0L, 0, 0, reversed = false, None, 86400L)
    val projectLink4 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 4L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom4, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom4), 0L, 0, 0, reversed = false, None, 86400L)
    val projectLink5 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 5L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom5, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom5), 0L, 0, 0, reversed = false, None, 86400L)
    val projectLink6 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 6L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom6, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom6), 0L, 0, 0, reversed = false, None, 86400L)
    val projectLink7 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 7L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom7, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom7), 0L, 0, 0, reversed = false, None, 86400L)
    val projectLink8 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 8L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom8, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom8), 0L, 0, 0, reversed = false, None, 86400L)
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
        val roadParts = pls.get.groupBy(pl => (pl.roadPart)).keys
        roadParts.foreach(rp => projectReservedPartDAO.reserveRoadPart(project.get.id, rp, "user"))
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
      Roadway(p.roadwayId, p.roadwayNumber, p.roadPart, p.administrativeClass, p.track, p.discontinuity, p.startAddrMValue, p.endAddrMValue, p.reversed, startDate, p.endDate,
        p.createdBy.getOrElse("-"), p.roadName, p.ely, TerminationCode.NoTermination, DateTime.now(), None))
  }

  test("Test defaultSectionCalculatorStrategy.assignMValues() " +
                 "When a new road is created with a combined track end" +
                 "Then calculation should succeed ") {
    /*         | |
      |--------|-|
      |--------|-|
      |
      |
    */

    val roadNumber = 46001
    val roadPartNumber = 1
    val roadName = None
    val createdBy = "Test"

    runWithRollback {
      val projectId = Sequences.nextViiteProjectId
      val project = Project(projectId, ProjectState.Incomplete, "f", createdBy, DateTime.now(), "", DateTime.now(), DateTime.now(), "", Seq(), Seq(), None, None)

      val roadPart = RoadPart(roadNumber,roadPartNumber)
      val newProjectLinks = Seq(
        ProjectLink(1000,roadPart,Track.RightSide,Discontinuity.Continuous,        0,0,0,0,None,None,Some(createdBy),"f7e653d4-a559-49c9-bd9c-383daeb1e654:1",0.0,118.932,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(388471.0,7292496.0,0.0), Point(388396.0,7292587.0,0.0)),projectId,RoadAddressChangeType.New,AdministrativeClass.Municipality,FrozenLinkInterface,118.932,0,0,14,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(1001,roadPart,Track.RightSide,Discontinuity.MinorDiscontinuity,0,0,0,0,None,None,Some(createdBy),"aa409eea-7ee9-44be-b7b8-a98880b44104:1",0.0, 24.644,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(388487.0,7292477.0,0.0), Point(388471.0,7292496.0,0.0)),projectId,RoadAddressChangeType.New,AdministrativeClass.Municipality,FrozenLinkInterface, 24.644,0,0,14,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(1002,roadPart,Track.RightSide,Discontinuity.Continuous,        0,0,0,0,None,None,Some(createdBy),"4377b337-c293-4805-b85a-70eb22dc5394:1",0.0, 21.437,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(388471.0,7292496.0,0.0), Point(388488.0,7292509.0,0.0)),projectId,RoadAddressChangeType.New,AdministrativeClass.Municipality,FrozenLinkInterface, 21.437,0,0,14,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(1003,roadPart,Track.RightSide,Discontinuity.Continuous,        0,0,0,0,None,None,Some(createdBy),"052fb370-4e13-4442-9fbb-fe5078413699:1",0.0,147.652,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(388354.0,7292405.0,0.0), Point(388471.0,7292496.0,0.0)),projectId,RoadAddressChangeType.New,AdministrativeClass.Municipality,FrozenLinkInterface,147.652,0,0,14,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(1004,roadPart,Track.RightSide,Discontinuity.Continuous,        0,0,0,0,None,None,Some(createdBy),"e3ce2286-1550-4a00-9e5b-c1df77fd48b9:1",0.0,109.001,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(388268.0,7292339.0,0.0), Point(388354.0,7292405.0,0.0)),projectId,RoadAddressChangeType.New,AdministrativeClass.Municipality,FrozenLinkInterface,109.001,0,0,14,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(1005,roadPart,Track.RightSide,Discontinuity.Continuous,        0,0,0,0,None,None,Some(createdBy),"963543fd-142b-4a31-ad11-a4c0c42f2ca2:1",0.0, 18.299,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(388254.0,7292327.0,0.0), Point(388268.0,7292339.0,0.0)),projectId,RoadAddressChangeType.New,AdministrativeClass.Municipality,FrozenLinkInterface, 18.299,0,0,14,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(1006,roadPart,Track.RightSide,Discontinuity.Continuous,        0,0,0,0,None,None,Some(createdBy),"13d2fc54-d767-4d55-bdaf-293848f61a8d:1",0.0,103.91 ,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(388172.0,7292263.0,0.0), Point(388254.0,7292327.0,0.0)),projectId,RoadAddressChangeType.New,AdministrativeClass.Municipality,FrozenLinkInterface,103.91 ,0,0,14,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(1007,roadPart,Track.LeftSide, Discontinuity.Continuous,        0,0,0,0,None,None,Some(createdBy),"cf00116c-2811-4110-a7cd-99485ee4bf2c:1",0.0,119.304,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(388488.0,7292509.0,0.0), Point(388412.0,7292601.0,0.0)),projectId,RoadAddressChangeType.New,AdministrativeClass.Municipality,FrozenLinkInterface,119.304,0,0,14,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(1008,roadPart,Track.LeftSide, Discontinuity.Continuous,        0,0,0,0,None,None,Some(createdBy),"f7a70aeb-0de6-489b-8ec4-195eb858f299:1",0.0, 24.427,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(388504.0,7292490.0,0.0), Point(388488.0,7292509.0,0.0)),projectId,RoadAddressChangeType.New,AdministrativeClass.Municipality,FrozenLinkInterface, 24.427,0,0,14,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(1009,roadPart,Track.LeftSide, Discontinuity.Continuous,        0,0,0,0,None,None,Some(createdBy),"ec9fefe3-4629-4f77-ae5c-a8b44f097c40:1",0.0, 21.327,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(388487.0,7292477.0,0.0), Point(388504.0,7292490.0,0.0)),projectId,RoadAddressChangeType.New,AdministrativeClass.Municipality,FrozenLinkInterface, 21.327,0,0,14,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(1010,roadPart,Track.LeftSide, Discontinuity.Continuous,        0,0,0,0,None,None,Some(createdBy),"527b1b90-4733-4f87-ac05-37cc2c37c2e8:1",0.0,147.618,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(388372.0,7292385.0,0.0), Point(388487.0,7292477.0,0.0)),projectId,RoadAddressChangeType.New,AdministrativeClass.Municipality,FrozenLinkInterface,147.618,0,0,14,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(1011,roadPart,Track.LeftSide, Discontinuity.Continuous,        0,0,0,0,None,None,Some(createdBy),"af221dc0-09ef-47c3-9f15-4729d334511a:1",0.0,109.24 ,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(388286.0,7292317.0,0.0), Point(388372.0,7292385.0,0.0)),projectId,RoadAddressChangeType.New,AdministrativeClass.Municipality,FrozenLinkInterface,109.24 ,0,0,14,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(1012,roadPart,Track.LeftSide, Discontinuity.Continuous,        0,0,0,0,None,None,Some(createdBy),"ae0da996-249f-474d-a4e1-0d02d2477735:1",0.0, 18.004,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(388272.0,7292305.0,0.0), Point(388286.0,7292317.0,0.0)),projectId,RoadAddressChangeType.New,AdministrativeClass.Municipality,FrozenLinkInterface, 18.004,0,0,14,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(1013,roadPart,Track.LeftSide, Discontinuity.MinorDiscontinuity,0,0,0,0,None,None,Some(createdBy),"5ae37dab-65a2-498c-b326-c9cfb9d89527:1",0.0,104.137,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(388191.0,7292241.0,0.0), Point(388272.0,7292305.0,0.0)),projectId,RoadAddressChangeType.New,AdministrativeClass.Municipality,FrozenLinkInterface,104.137,0,0,14,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(1014,roadPart,Track.Combined, Discontinuity.Continuous,        0,0,0,0,None,None,Some(createdBy),"1faa7472-31cf-4fa4-b982-0b7a94d45101:1",0.0, 29.462,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(388191.0,7292241.0,0.0), Point(388172.0,7292263.0,0.0)),projectId,RoadAddressChangeType.New,AdministrativeClass.Municipality,FrozenLinkInterface, 29.462,0,0,14,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(1015,roadPart,Track.Combined, Discontinuity.EndOfRoad,         0,0,0,0,None,None,Some(createdBy),"a8bb33ba-b663-474d-9a92-047a2af66a86:1",0.0,117.163,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(388265.0,7292150.0,0.0), Point(388191.0,7292241.0,0.0)),projectId,RoadAddressChangeType.New,AdministrativeClass.Municipality,FrozenLinkInterface,117.163,0,0,14,reversed = false,None,1652179948783L,0,roadName,None,None,None,None)
      )

      projectDAO.create(Project(projectId, ProjectState.Incomplete, "f", createdBy, DateTime.now(), "", DateTime.now(), DateTime.now(),"", Seq(), Seq(), None, None))
      projectReservedPartDAO.reserveRoadPart(projectId, roadPart, createdBy)
      projectLinkDAO.create(newProjectLinks)

      val projectLinksWithAssignedValues = defaultSectionCalculatorStrategy.assignMValues(newProjectLinks, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])

      // All Should have addresses
      projectLinksWithAssignedValues.forall(!_.isNotCalculated) should be(true)
      projectLinksWithAssignedValues.forall(_.sideCode == SideCode.AgainstDigitizing) should be(true)
      projectLinksWithAssignedValues.sortBy(_.id).zip(newProjectLinks.sortBy(_.id)).forall { case (pl1, pl2) => {pl1.discontinuity == pl2.discontinuity && pl1.track == pl2.track}} should be(true)
    }
      // EndOfRoad reversed
    runWithRollback {
      val projectId = Sequences.nextViiteProjectId
      val project = Project(projectId, ProjectState.Incomplete, "f", createdBy, DateTime.now(), "", DateTime.now(), DateTime.now(), "", Seq(), Seq(), None, None)

      val roadPart = RoadPart(roadNumber,roadPartNumber)
      val newProjectLinks = Seq(
        ProjectLink(1002,roadPart,Track.Combined, Discontinuity.Continuous,0,0,0,0,None,None,Some(createdBy),"a8bb33ba-b663-474d-9a92-047a2af66a86:1",0.0,117.163,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(388265.0,7292150.0,0.0), Point(388191.0,7292241.0,0.0)),projectId,RoadAddressChangeType.New,AdministrativeClass.Municipality,FrozenLinkInterface,117.163,0,0,14,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(1003,roadPart,Track.Combined, Discontinuity.Continuous,0,0,0,0,None,None,Some(createdBy),"1faa7472-31cf-4fa4-b982-0b7a94d45101:1",0.0, 29.462,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(388191.0,7292241.0,0.0), Point(388172.0,7292263.0,0.0)),projectId,RoadAddressChangeType.New,AdministrativeClass.Municipality,FrozenLinkInterface, 29.462,0,0,14,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(1004,roadPart,Track.RightSide,Discontinuity.Continuous,0,0,0,0,None,None,Some(createdBy),"f7a70aeb-0de6-489b-8ec4-195eb858f299:1",0.0, 24.427,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(388504.0,7292490.0,0.0), Point(388488.0,7292509.0,0.0)),projectId,RoadAddressChangeType.New,AdministrativeClass.Municipality,FrozenLinkInterface, 24.427,0,0,14,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(1005,roadPart,Track.RightSide,Discontinuity.EndOfRoad, 0,0,0,0,None,None,Some(createdBy),"cf00116c-2811-4110-a7cd-99485ee4bf2c:1",0.0,119.304,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(388488.0,7292509.0,0.0), Point(388412.0,7292601.0,0.0)),projectId,RoadAddressChangeType.New,AdministrativeClass.Municipality,FrozenLinkInterface,119.304,0,0,14,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(1006,roadPart,Track.RightSide,Discontinuity.Continuous,0,0,0,0,None,None,Some(createdBy),"5ae37dab-65a2-498c-b326-c9cfb9d89527:1",0.0,104.137,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(388191.0,7292241.0,0.0), Point(388272.0,7292305.0,0.0)),projectId,RoadAddressChangeType.New,AdministrativeClass.Municipality,FrozenLinkInterface,104.137,0,0,14,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(1007,roadPart,Track.RightSide,Discontinuity.Continuous,0,0,0,0,None,None,Some(createdBy),"ae0da996-249f-474d-a4e1-0d02d2477735:1",0.0, 18.004,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(388272.0,7292305.0,0.0), Point(388286.0,7292317.0,0.0)),projectId,RoadAddressChangeType.New,AdministrativeClass.Municipality,FrozenLinkInterface, 18.004,0,0,14,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(1008,roadPart,Track.RightSide,Discontinuity.Continuous,0,0,0,0,None,None,Some(createdBy),"af221dc0-09ef-47c3-9f15-4729d334511a:1",0.0,109.24 ,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(388286.0,7292317.0,0.0), Point(388372.0,7292385.0,0.0)),projectId,RoadAddressChangeType.New,AdministrativeClass.Municipality,FrozenLinkInterface,109.24 ,0,0,14,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(1009,roadPart,Track.RightSide,Discontinuity.Continuous,0,0,0,0,None,None,Some(createdBy),"527b1b90-4733-4f87-ac05-37cc2c37c2e8:1",0.0,147.618,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(388372.0,7292385.0,0.0), Point(388487.0,7292477.0,0.0)),projectId,RoadAddressChangeType.New,AdministrativeClass.Municipality,FrozenLinkInterface,147.618,0,0,14,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(1010,roadPart,Track.RightSide,Discontinuity.Continuous,0,0,0,0,None,None,Some(createdBy),"ec9fefe3-4629-4f77-ae5c-a8b44f097c40:1",0.0, 21.327,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(388487.0,7292477.0,0.0), Point(388504.0,7292490.0,0.0)),projectId,RoadAddressChangeType.New,AdministrativeClass.Municipality,FrozenLinkInterface, 21.327,0,0,14,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(1000,roadPart,Track.LeftSide, Discontinuity.Continuous,0,0,0,0,None,None,Some(createdBy),"aa409eea-7ee9-44be-b7b8-a98880b44104:1",0.0, 24.644,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(388487.0,7292477.0,0.0), Point(388471.0,7292496.0,0.0)),projectId,RoadAddressChangeType.New,AdministrativeClass.Municipality,FrozenLinkInterface, 24.644,0,0,14,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(1001,roadPart,Track.LeftSide, Discontinuity.EndOfRoad, 0,0,0,0,None,None,Some(createdBy),"f7e653d4-a559-49c9-bd9c-383daeb1e654:1",0.0,118.932,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(388471.0,7292496.0,0.0), Point(388396.0,7292587.0,0.0)),projectId,RoadAddressChangeType.New,AdministrativeClass.Municipality,FrozenLinkInterface,118.932,0,0,14,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(1011,roadPart,Track.LeftSide, Discontinuity.Continuous,0,0,0,0,None,None,Some(createdBy),"13d2fc54-d767-4d55-bdaf-293848f61a8d:1",0.0,103.91 ,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(388172.0,7292263.0,0.0), Point(388254.0,7292327.0,0.0)),projectId,RoadAddressChangeType.New,AdministrativeClass.Municipality,FrozenLinkInterface,103.91 ,0,0,14,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(1012,roadPart,Track.LeftSide, Discontinuity.Continuous,0,0,0,0,None,None,Some(createdBy),"963543fd-142b-4a31-ad11-a4c0c42f2ca2:1",0.0, 18.299,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(388254.0,7292327.0,0.0), Point(388268.0,7292339.0,0.0)),projectId,RoadAddressChangeType.New,AdministrativeClass.Municipality,FrozenLinkInterface, 18.299,0,0,14,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(1013,roadPart,Track.LeftSide, Discontinuity.Continuous,0,0,0,0,None,None,Some(createdBy),"e3ce2286-1550-4a00-9e5b-c1df77fd48b9:1",0.0,109.001,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(388268.0,7292339.0,0.0), Point(388354.0,7292405.0,0.0)),projectId,RoadAddressChangeType.New,AdministrativeClass.Municipality,FrozenLinkInterface,109.001,0,0,14,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(1014,roadPart,Track.LeftSide, Discontinuity.Continuous,0,0,0,0,None,None,Some(createdBy),"052fb370-4e13-4442-9fbb-fe5078413699:1",0.0,147.652,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(388354.0,7292405.0,0.0), Point(388471.0,7292496.0,0.0)),projectId,RoadAddressChangeType.New,AdministrativeClass.Municipality,FrozenLinkInterface,147.652,0,0,14,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(1015,roadPart,Track.LeftSide, Discontinuity.MinorDiscontinuity,0,0,0,0,None,None,Some(createdBy),"4377b337-c293-4805-b85a-70eb22dc5394:1",0.0,21.437,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(388471.0,7292496.0,0.0), Point(388488.0,7292509.0,0.0)),projectId,RoadAddressChangeType.New,AdministrativeClass.Municipality,FrozenLinkInterface,21.437,0,0,14,reversed = false,None,1652179948783L,0,roadName,None,None,None,None)
      )

      projectDAO.create(project)
      projectReservedPartDAO.reserveRoadPart(projectId, roadPart, createdBy)
      projectLinkDAO.create(newProjectLinks)

      val projectLinksWithAssignedValues = defaultSectionCalculatorStrategy.assignMValues(newProjectLinks, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])

      // All Should have addresses
      projectLinksWithAssignedValues.forall(!_.isNotCalculated) should be(true)
      projectLinksWithAssignedValues.forall(_.sideCode == SideCode.TowardsDigitizing) should be(true)
      projectLinksWithAssignedValues.sortBy(_.id).zip(newProjectLinks.sortBy(_.id)).forall { case (pl1, pl2) => {pl1.discontinuity == pl2.discontinuity && pl1.track == pl2.track}} should be(true)
    }
  }

    test("Test defaultSectionCalculatorStrategy.assignMValues() " +
         "When an existitng road is transferred as a two track part to middle of another road " +
         "Then calculation should succeed with length change in the middle cause by averaging.") {
      /*             ____ 22618
                    /    \
              |-----|----|-----| 110
      */
      runWithRollback {
        val roadPart            = RoadPart(110,22)
        val createdBy           = "Test"
        val roadName            = None
        val projectId           = Sequences.nextViiteProjectId
        val transferredRoadPart = RoadPart(22618, 995)
        val linearLocationId    = Sequences.nextLinearLocationId
        val roadwayId           = Sequences.nextRoadwayId

        val projectLinks = Seq(
          ProjectLink(Sequences.nextProjectLinkId,roadPart,Track.RightSide,Discontinuity.Continuous,   0,  136,   0,  136,None,None,Some(createdBy),"ad920f09-0ed4-4eb2-86da-5ae8d8f10ec0:1",0.0,135.756,SideCode.AgainstDigitizing,(NoCP,           NoCP           ),(RoadAddressCP,  RoadAddressCP  ),List(Point(308364.0,6698821.0,0.0), Point(308365.0,6698832.0,0.0), Point(308480.0,6698861.0,0.0)),                               projectId,RoadAddressChangeType.Transfer, AdministrativeClass.State,FrozenLinkInterface,124.109,roadwayId+3,linearLocationId,  2,false,None,1590620411000L,332392368L,roadName,None,None,None,None,None),
          ProjectLink(Sequences.nextProjectLinkId,roadPart,Track.Combined, Discontinuity.Continuous,   0,  907,   0,  907,None,None,Some(createdBy),"f324e593-1bbc-4d3c-8729-1adc527354bf:1",0.0,905.016,SideCode.AgainstDigitizing,(RoadAddressCP,  JunctionPointCP),(RoadAddressCP,  JunctionPointCP),List(Point(308480.0,6698861.0,0.0), Point(308685.0,6698911.0,0.0), Point(309040.0,6698965.0,0.0), Point(309371.0,6699012.0,0.0)),projectId,RoadAddressChangeType.Unchanged,AdministrativeClass.State,FrozenLinkInterface,334.521,roadwayId,  linearLocationId+1,2,false,None,1590620411000L,  7484795L,roadName,None,None,None,None,None),
          ProjectLink(Sequences.nextProjectLinkId,roadPart,Track.LeftSide, Discontinuity.Continuous, 907, 1030, 907, 1030,None,None,Some(createdBy),"e168f5ec-0fe5-4520-8d8d-76d33a6972b9:1",0.0,122.719,SideCode.AgainstDigitizing,(JunctionPointCP,JunctionPointCP),(JunctionPointCP,JunctionPointCP),List(Point(308364.0,6698821.0,0.0), Point(308480.0,6698861.0,0.0)),                                                              projectId,RoadAddressChangeType.Transfer, AdministrativeClass.State,FrozenLinkInterface,122.719,roadwayId+1,linearLocationId+2,2,false,None,1590620411000L,  7484796L,roadName,None,None,None,None,None),
          ProjectLink(Sequences.nextProjectLinkId,roadPart,Track.Combined, Discontinuity.Continuous,1030,12995,1030,12995,None,None,Some(createdBy),"86f1e841-366d-423e-84a3-cf7703a656ae:1",0.0,11942.217999999999,SideCode.AgainstDigitizing,(JunctionPointCP,NoCP),(JunctionPointCP,RoadAddressCP  ),List(Point(296779.0,6698725.0,0.0), Point(308364.0,6698821.0,0.0)),                                                              projectId,RoadAddressChangeType.Transfer, AdministrativeClass.State,FrozenLinkInterface,318.95 ,roadwayId+2,linearLocationId+3,2,false,None,1590620411000L,  7484797L,roadName,None,None,None,None,None)
        )

        val roadways = Seq(
          Roadway(roadwayId,  7484795,   roadPart,        AdministrativeClass.State,Track.Combined,Discontinuity.Continuous,   0,  907,false,DateTime.parse("2008-11-15T00:00:00.000+02:00"),None,"import",roadName,2,TerminationCode.NoTermination,DateTime.parse("2017-05-08T00:00:00.000+03:00"),None),
          Roadway(roadwayId+1,7484795,   roadPart,        AdministrativeClass.State,Track.Combined,Discontinuity.Continuous, 907, 1030,false,DateTime.parse("2008-11-15T00:00:00.000+02:00"),None,"import",roadName,2,TerminationCode.NoTermination,DateTime.parse("2017-05-08T00:00:00.000+03:00"),None),
          Roadway(roadwayId+2,7484795,   roadPart,        AdministrativeClass.State,Track.Combined,Discontinuity.Continuous,1030,12995,false,DateTime.parse("2008-11-15T00:00:00.000+02:00"),None,"import",roadName,2,TerminationCode.NoTermination,DateTime.parse("2017-05-08T00:00:00.000+03:00"),None),
          Roadway(roadwayId+3,332392368L,transferredRoadPart,AdministrativeClass.State,Track.Combined,Discontinuity.Continuous,0,  136,false,DateTime.parse("2021-09-01T00:00:00.000+03:00"),None,"import",roadName,2,TerminationCode.NoTermination,DateTime.parse("2021-10-06T00:00:00.000+03:00"),None)
        )

        val linearLocations = Seq(
          LinearLocation(linearLocationId,1.0,"ad920f09-0ed4-4eb2-86da-5ae8d8f10ec0:1",0.0,135.756,SideCode.AgainstDigitizing,1590620411000L,(CalibrationPointReference(Some(0),Some(RoadAddressCP)),CalibrationPointReference(Some(136),Some(RoadAddressCP))),List(Point(308364.0,6698821.0,0.0), Point(308480.0,6698861.0,0.0)),FrozenLinkInterface,332392368L,Some(DateTime.parse("2021-10-06T00:00:00.000+03:00")),None),
          LinearLocation(linearLocationId+1,1.0,"f324e593-1bbc-4d3c-8729-1adc527354bf:1",0.0,905.016,SideCode.AgainstDigitizing,1590620411000L,(CalibrationPointReference(Some(0),Some(RoadAddressCP)),CalibrationPointReference(Some(907),Some(JunctionPointCP))),List(Point(308480.0,6698861.0,0.0), Point(309371.0,6699012.0,0.0)),FrozenLinkInterface,7484795L,Some(DateTime.parse("2017-05-08T00:00:00.000+03:00")),None),
          LinearLocation(linearLocationId+2,1.0,"e168f5ec-0fe5-4520-8d8d-76d33a6972b9:1",0.0,122.719,SideCode.AgainstDigitizing,1590620411000L,(CalibrationPointReference(Some(907),Some(JunctionPointCP)),CalibrationPointReference(Some(1030),Some(JunctionPointCP))),List(Point(308364.0,6698821.0,0.0), Point(308480.0,6698861.0,0.0)),FrozenLinkInterface,7484796L,Some(DateTime.parse("2017-05-08T00:00:00.000+03:00")),None),
          LinearLocation(linearLocationId+3,1.0,"86f1e841-366d-423e-84a3-cf7703a656ae:1",0.0,11942.218,SideCode.AgainstDigitizing,1590620411000L,(CalibrationPointReference(Some(1030),Some(JunctionPointCP)),CalibrationPointReference(Some(12995),Some(RoadAddressCP))),List(Point(296779.0,6698725.0,0.0), Point(308364.0,6698821.0,0.0)),FrozenLinkInterface,7484797L,Some(DateTime.parse("2017-05-08T00:00:00.000+03:00")),None)
        )

        projectDAO.create(Project(projectId, ProjectState.Incomplete, "f", createdBy, DateTime.now(), "", DateTime.now(), DateTime.now(),"", Seq(), Seq(), None, None))
        projectReservedPartDAO.reserveRoadPart(projectId, roadPart, createdBy)
        projectReservedPartDAO.reserveRoadPart(projectId, transferredRoadPart, createdBy)
        roadwayDAO.create(roadways)
        linearLocationDAO.create(linearLocations)
        projectLinkDAO.create(projectLinks)

        val projectLinksWithAssignedValues = defaultSectionCalculatorStrategy.assignMValues(projectLinks, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])

        val fl = projectLinksWithAssignedValues.filter(_.startAddrMValue == 0)
        fl should have size 1
        fl.head.endAddrMValue should be(roadways.find(_.startAddrMValue == 0).get.endAddrMValue)

        val mdl = projectLinksWithAssignedValues.filter(_.startAddrMValue == fl.head.endAddrMValue)
        mdl should have size 2
        mdl.head.endAddrMValue should be (mdl.last.endAddrMValue)

        val mx = projectLinksWithAssignedValues.filter(_.startAddrMValue == mdl.head.endAddrMValue)
        mx should have size 1
        mx.head.endAddrMValue - mx.head.startAddrMValue should be(mx.head.originalEndAddrMValue - mx.head.originalStartAddrMValue)
      }
    }

  test("Test defaultSectionCalculatorStrategy.assignMValues() and findStartingPoints When there is MinorDiscontinuity + Continuous + EndOfRoad from east to west intention " +
       "Then return the same project links, but now with correct MValues and directions") {
    runWithRollback {
      val roadPart       = RoadPart(46001, 1)
      val createdBy      = "Test"
      val roadName       = None
      val projectId      = Sequences.nextViiteProjectId

      val newProjectLinks = Seq(
        ProjectLink(1000, roadPart, Track.Combined, Discontinuity.Continuous,         0, 0, 0, 0, None, None, Some(createdBy), "5f60a893-52d8-4de1-938b-2e4a04accc8c:1", 0.0, 147.232, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), List(Point(388281.0, 7292495.0, 0.0), Point(388396.0, 7292587.0, 0.0)), projectId, RoadAddressChangeType.New, AdministrativeClass.Municipality, FrozenLinkInterface, 147.232, 0, 0, 14, reversed = false, None, 1652179948783L, 0, roadName, None, None, None, None, None),
        ProjectLink(1001, roadPart, Track.Combined, Discontinuity.EndOfRoad,          0, 0, 0, 0, None, None, Some(createdBy), "49bd02c4-697f-4e88-a7b9-59a33549eeec:1", 0.0, 108.62,  SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), List(Point(388196.0, 7292427.0, 0.0), Point(388281.0, 7292495.0, 0.0)), projectId, RoadAddressChangeType.New, AdministrativeClass.Municipality, FrozenLinkInterface, 108.62,  0, 0, 14, reversed = false, None, 1652179948783L, 0, roadName, None, None, None, None, None),
        ProjectLink(1002, roadPart, Track.Combined, Discontinuity.MinorDiscontinuity, 0, 0, 0, 0, None, None, Some(createdBy), "a87b5257-72cd-4d48-9fff-54e81782cb64:1", 0.0, 105.259, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), List(Point(388412.0, 7292601.0, 0.0), Point(388494.0, 7292667.0, 0.0)), projectId, RoadAddressChangeType.New, AdministrativeClass.Municipality, FrozenLinkInterface, 105.259, 0, 0, 14, reversed = false, None, 1652179948783L, 0, roadName, None, None, None, None, None)
      )
      val startingPointsForCalculations = defaultSectionCalculatorStrategy.findStartingPoints(newProjectLinks, Seq.empty[ProjectLink], Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
      val startPoint = newProjectLinks.find(_.discontinuity == Discontinuity.MinorDiscontinuity).get.geometry.last
      startingPointsForCalculations should be((startPoint, startPoint))

      val projectLinksWithAssignedValues = defaultSectionCalculatorStrategy.assignMValues(newProjectLinks, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
      // All Should have addresses
      projectLinksWithAssignedValues.forall(!_.isNotCalculated) should be(true)
      projectLinksWithAssignedValues.forall(_.sideCode == SideCode.AgainstDigitizing) should be(true)
      val intendedOrder = Seq(newProjectLinks.last, newProjectLinks.head, newProjectLinks(1))
      projectLinksWithAssignedValues.sortBy(_.endAddrMValue).zip(intendedOrder).forall { case (pl1, pl2) => {pl1.id == pl2.id}} should be(true)
    }
    // An opposite direction case
    runWithRollback {
      val roadPart       = RoadPart(46001,1)
      val createdBy      = "Test"
      val roadName       = None
      val projectId      = Sequences.nextViiteProjectId

      val newProjectLinks = Seq(
        ProjectLink(1000,roadPart,Track.Combined,Discontinuity.Continuous,        0,0,0,0,None,None,Some(createdBy),"49bd02c4-697f-4e88-a7b9-59a33549eeec:1",0.0,108.62, SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(388196.0,7292427.0,0.0), Point(388281.0,7292495.0,0.0)),projectId,RoadAddressChangeType.New,AdministrativeClass.Municipality,FrozenLinkInterface,108.62 ,0,0,14,reversed = false,None,1652179948783L,0,roadName,None,None,None,None,None),
        ProjectLink(1001,roadPart,Track.Combined,Discontinuity.EndOfRoad,         0,0,0,0,None,None,Some(createdBy),"5f60a893-52d8-4de1-938b-2e4a04accc8c:1",0.0,147.232,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(388281.0,7292495.0,0.0), Point(388396.0,7292587.0,0.0)),projectId,RoadAddressChangeType.New,AdministrativeClass.Municipality,FrozenLinkInterface,147.232,0,0,14,reversed = false,None,1652179948783L,0,roadName,None,None,None,None,None),
        ProjectLink(1002,roadPart,Track.Combined,Discontinuity.MinorDiscontinuity,0,0,0,0,None,None,Some(createdBy),"c502334c-b2df-43d4-b5fd-e6ec384e5c9c:1",0.0,101.59, SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(388102.0,7292352.0,0.0), Point(388181.0,7292415.0,0.0)),projectId,RoadAddressChangeType.New,AdministrativeClass.Municipality,FrozenLinkInterface,101.59, 0,0,14,reversed = false,None,1652179948783L,0,roadName,None,None,None,None,None)
      )
      val startingPointsForCalculations = defaultSectionCalculatorStrategy.findStartingPoints(newProjectLinks, Seq.empty[ProjectLink], Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
      val startPoint = newProjectLinks.find(_.discontinuity == Discontinuity.MinorDiscontinuity).get.geometry.head
      startingPointsForCalculations should be((startPoint, startPoint))

      val projectLinksWithAssignedValues = defaultSectionCalculatorStrategy.assignMValues(newProjectLinks, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
      // All Should have addresses
      projectLinksWithAssignedValues.forall(!_.isNotCalculated) should be(true)
      projectLinksWithAssignedValues.forall(_.sideCode == SideCode.TowardsDigitizing) should be(true)
      val intendedOrder = Seq(newProjectLinks.last, newProjectLinks.head, newProjectLinks(1))
      projectLinksWithAssignedValues.sortBy(_.endAddrMValue).zip(intendedOrder).forall { case (pl1, pl2) => {pl1.id == pl2.id}} should be(true)
    }
  }

    test("Test defaultSectionCalculatorStrategy.assignMValues() " +
          "When two track road has new links and terminations in the middle of the road " +
          "Then addressess and geometries should be continuous.") {
      // VIITE-2946
      runWithRollback {
      val roadPart  = RoadPart(15, 1)
      val createdBy = "Test"
      val roadName = None
      val projectId = Sequences.nextViiteProjectId

      val projectLinks = Seq(
        ProjectLink(1022,roadPart,Track.Combined, Discontinuity.Continuous,      0, 162,   0, 162,None,None,Some(createdBy),"004caef8-4af1-43a9-aa5e-c76d1ccee9ee:2",  0.0  ,164.967,SideCode.AgainstDigitizing,(JunctionPointCP,NoCP           ),(JunctionPointCP,NoCP           ),List(Point(496903.0,6703278.0,0.0), Point(497030.0,6703383.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,FrozenLinkInterface,164.967,70627,437329,3,reversed = false,None,1654877322508L,148122025,roadName,None,None,None,None,None),
        ProjectLink(1000,roadPart,Track.RightSide,Discontinuity.Continuous,      0,   0,   0,   0,None,None,Some(createdBy),"21124a47-8499-4c7f-81d6-1bca440d05d1:1",  0.0  , 15.111,SideCode.Unknown,          (NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(495536.0,6703758.0,0.0), Point(495532.0,6703773.0,0.0)),projectId,RoadAddressChangeType.New,        AdministrativeClass.State,       FrozenLinkInterface, 15.111,    0,     0,3,reversed = false,None,1666100232908L,        0,roadName,None,None,None,None,None),
        ProjectLink(1001,roadPart,Track.RightSide,Discontinuity.Continuous,      0,   0,   0,   0,None,None,Some(createdBy),"8d8e4217-dbf4-4589-b268-51eb2cac8515:1",  0.0  ,167.012,SideCode.Unknown,          (NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(495532.0,6703773.0,0.0), Point(495497.0,6703936.0,0.0)),projectId,RoadAddressChangeType.New,        AdministrativeClass.State,       FrozenLinkInterface,167.012,    0,     0,3,reversed = false,None,1666100232908L,        0,roadName,None,None,None,None,None),
        ProjectLink(1003,roadPart,Track.RightSide,Discontinuity.Continuous,      0,   0,   0,   0,None,None,Some(createdBy),"b7f198a9-1386-4113-89b0-aa073ed5814a:1",  0.0  ,376.583,SideCode.Unknown,          (NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(495609.0,6703389.0,0.0), Point(495536.0,6703758.0,0.0)),projectId,RoadAddressChangeType.New,        AdministrativeClass.State,       FrozenLinkInterface,376.583,    0,     0,3,reversed = false,None,1666100232908L,        0,roadName,None,None,None,None,None),
        ProjectLink(1005,roadPart,Track.RightSide,Discontinuity.Continuous,      0,   0,   0,   0,None,None,Some(createdBy),"02c5a653-6bf7-4f00-9ad5-d693829dd16c:1",  0.0  , 27.312,SideCode.Unknown,          (NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(495478.0,6704199.0,0.0), Point(495479.0,6704226.0,0.0)),projectId,RoadAddressChangeType.New,        AdministrativeClass.State,       FrozenLinkInterface, 27.312,    0,     0,3,reversed = false,None,1666100232908L,        0,roadName,None,None,None,None,None),
        ProjectLink(1010,roadPart,Track.RightSide,Discontinuity.Continuous,      0,   0,   0,   0,None,None,Some(createdBy),"4fa38e57-7cdd-438e-bc51-cc5e26f4613f:1",  0.0  ,193.546,SideCode.Unknown,          (NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(495248.0,6704906.0,0.0), Point(495161.0,6705077.0,0.0)),projectId,RoadAddressChangeType.New,        AdministrativeClass.State,       FrozenLinkInterface,193.546,    0,     0,3,reversed = false,None,1666100232908L,        0,roadName,None,None,None,None,None),
        ProjectLink(1011,roadPart,Track.RightSide,Discontinuity.Continuous,      0,   0,   0,   0,None,None,Some(createdBy),"cda8745e-b27c-4193-a163-8a32c705218c:1",  0.0  , 14.235,SideCode.Unknown,          (NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(495254.0,6704893.0,0.0), Point(495248.0,6704906.0,0.0)),projectId,RoadAddressChangeType.New,        AdministrativeClass.State,       FrozenLinkInterface, 14.235,    0,     0,3,reversed = false,None,1666100232908L,        0,roadName,None,None,None,None,None),
        ProjectLink(1012,roadPart,Track.RightSide,Discontinuity.Continuous,      0,   0,   0,   0,None,None,Some(createdBy),"a9f8fa40-567d-43c9-add9-31bf6fa61efc:1",  0.0  ,195.564,SideCode.Unknown,          (NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(495345.0,6704719.0,0.0), Point(495254.0,6704893.0,0.0)),projectId,RoadAddressChangeType.New,        AdministrativeClass.State,       FrozenLinkInterface,195.564,    0,     0,3,reversed = false,None,1666100232908L,        0,roadName,None,None,None,None,None),
        ProjectLink(1013,roadPart,Track.RightSide,Discontinuity.Continuous,      0,   0,   0,   0,None,None,Some(createdBy),"d1fb1d36-1033-460f-b622-0511c28ce242:1",  0.0  ,520.268,SideCode.Unknown,          (NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(495479.0,6704226.0,0.0), Point(495345.0,6704719.0,0.0)),projectId,RoadAddressChangeType.New,        AdministrativeClass.State,       FrozenLinkInterface,520.268,    0,     0,3,reversed = false,None,1666100232908L,        0,roadName,None,None,None,None,None),
        ProjectLink(1017,roadPart,Track.RightSide,Discontinuity.Continuous,      0,   0,   0,   0,None,None,Some(createdBy),"9e521e98-df86-491b-b9d9-6379164f74e0:1",  0.0  ,264.245,SideCode.Unknown,          (NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(495497.0,6703936.0,0.0), Point(495478.0,6704199.0,0.0)),projectId,RoadAddressChangeType.New,        AdministrativeClass.State,       FrozenLinkInterface,264.245,    0,     0,3,reversed = false,None,1666100232908L,        0,roadName,None,None,None,None,None),
        ProjectLink(1018,roadPart,Track.RightSide,Discontinuity.Continuous,      0,   0,   0,   0,None,None,Some(createdBy),"03d309e1-171b-4a28-85cb-9ff8d2e2f776:1",  0.0  ,  4.528,SideCode.Unknown,          (NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(495610.0,6703384.0,0.0), Point(495609.0,6703389.0,0.0)),projectId,RoadAddressChangeType.New,        AdministrativeClass.State,       FrozenLinkInterface,  4.528,    0,     0,3,reversed = false,None,1666100232908L,        0,roadName,None,None,None,None,None),
        ProjectLink(1019,roadPart,Track.RightSide,Discontinuity.Continuous,      0,   0,   0,   0,None,None,Some(createdBy),"12988d98-ea82-4474-8236-e64d22cde7e2:1",  0.0  , 76.807,SideCode.Unknown,          (NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(495619.0,6703308.0,0.0), Point(495610.0,6703384.0,0.0)),projectId,RoadAddressChangeType.New,        AdministrativeClass.State,       FrozenLinkInterface, 76.807,    0,     0,3,reversed = false,None,1666100232908L,        0,roadName,None,None,None,None,None),
        ProjectLink(1002,roadPart,Track.LeftSide, Discontinuity.Continuous,      0,   0,   0,   0,None,None,Some(createdBy),"11235140-ae12-4abe-81c0-610cc7b4d68e:1",  0.0  , 81.195,SideCode.Unknown,          (NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(495610.0,6703309.0,0.0), Point(495597.0,6703389.0,0.0)),projectId,RoadAddressChangeType.New,        AdministrativeClass.State,       FrozenLinkInterface, 81.195,    0,     0,3,reversed = false,None,1666100232908L,        0,roadName,None,None,None,None,None),
        ProjectLink(1004,roadPart,Track.LeftSide, Discontinuity.Continuous,      0,   0,   0,   0,None,None,Some(createdBy),"f35325e6-af41-410b-8aa8-4cd8904acaa1:1",  0.0  ,357.789,SideCode.Unknown,          (NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(495594.0,6703403.0,0.0), Point(495525.0,6703754.0,0.0)),projectId,RoadAddressChangeType.New,        AdministrativeClass.State,       FrozenLinkInterface,357.789,    0,     0,3,reversed = false,None,1666100232908L,        0,roadName,None,None,None,None,None),
        ProjectLink(1006,roadPart,Track.LeftSide, Discontinuity.Continuous,      0,   0,   0,   0,None,None,Some(createdBy),"73be24a0-828a-445c-89f6-399a8baf399d:1",  0.0  ,  8.365,SideCode.Unknown,          (NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(495596.0,6703395.0,0.0), Point(495594.0,6703403.0,0.0)),projectId,RoadAddressChangeType.New,        AdministrativeClass.State,       FrozenLinkInterface,  8.365,    0,     0,3,reversed = false,None,1666100232908L,        0,roadName,None,None,None,None,None),
        ProjectLink(1007,roadPart,Track.LeftSide, Discontinuity.Continuous,      0,   0,   0,   0,None,None,Some(createdBy),"d23af145-e71b-4c65-8c67-846a9bf855a1:1",  0.0  ,  5.332,SideCode.Unknown,          (NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(495597.0,6703389.0,0.0), Point(495596.0,6703395.0,0.0)),projectId,RoadAddressChangeType.New,        AdministrativeClass.State,       FrozenLinkInterface,  5.332,    0,     0,3,reversed = false,None,1666100232908L,        0,roadName,None,None,None,None,None),
        ProjectLink(1008,roadPart,Track.LeftSide, Discontinuity.Continuous,      0,   0,   0,   0,None,None,Some(createdBy),"e0f5febf-0719-4dc3-9c09-7fee3fd8e465:1",  0.0  ,192.276,SideCode.Unknown,          (NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(495239.0,6704903.0,0.0), Point(495161.0,6705077.0,0.0)),projectId,RoadAddressChangeType.New,        AdministrativeClass.State,       FrozenLinkInterface,192.276,    0,     0,3,reversed = false,None,1666100232908L,        0,roadName,None,None,None,None,None),
        ProjectLink(1009,roadPart,Track.LeftSide, Discontinuity.Continuous,      0,   0,   0,   0,None,None,Some(createdBy),"c73ac8b7-1d29-4a99-98c1-fba9abf113f0:1",  0.0  , 14.407,SideCode.Unknown,          (NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(495245.0,6704890.0,0.0), Point(495239.0,6704903.0,0.0)),projectId,RoadAddressChangeType.New,        AdministrativeClass.State,       FrozenLinkInterface, 14.407,    0,     0,3,reversed = false,None,1666100232908L,        0,roadName,None,None,None,None,None),
        ProjectLink(1014,roadPart,Track.LeftSide, Discontinuity.Continuous,      0,   0,   0,   0,None,None,Some(createdBy),"ff6c53f5-fdc7-4082-9e80-0bfd541d8ab9:1",  0.0  ,344.485,SideCode.Unknown,          (NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(495500.0,6703861.0,0.0), Point(495469.0,6704203.0,0.0)),projectId,RoadAddressChangeType.New,        AdministrativeClass.State,       FrozenLinkInterface,344.485,    0,     0,3,reversed = false,None,1666100232908L,        0,roadName,None,None,None,None,None),
        ProjectLink(1015,roadPart,Track.LeftSide, Discontinuity.Continuous,      0,   0,   0,   0,None,None,Some(createdBy),"521c6ba1-ac37-439a-930f-561feb42cd06:1",  0.0  , 28.356,SideCode.Unknown,          (NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(495469.0,6704203.0,0.0), Point(495470.0,6704231.0,0.0)),projectId,RoadAddressChangeType.New,        AdministrativeClass.State,       FrozenLinkInterface, 28.356,    0,     0,3,reversed = false,None,1666100232908L,        0,roadName,None,None,None,None,None),
        ProjectLink(1016,roadPart,Track.LeftSide, Discontinuity.Continuous,      0,   0,   0,   0,None,None,Some(createdBy),"29363d1c-0078-433f-8eca-7c6500937665:1",  0.0  ,708.123,SideCode.Unknown,          (NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(495470.0,6704231.0,0.0), Point(495245.0,6704890.0,0.0)),projectId,RoadAddressChangeType.New,        AdministrativeClass.State,       FrozenLinkInterface,708.123,    0,     0,3,reversed = false,None,1666100232908L,        0,roadName,None,None,None,None,None),
        ProjectLink(1020,roadPart,Track.LeftSide, Discontinuity.Continuous,      0,   0,   0,   0,None,None,Some(createdBy),"d4e0a1c1-a8ac-457e-a968-e0e1cfba49e1:1",  0.0  , 16.552,SideCode.Unknown,          (NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(495525.0,6703754.0,0.0), Point(495522.0,6703770.0,0.0)),projectId,RoadAddressChangeType.New,        AdministrativeClass.State,       FrozenLinkInterface, 16.552,    0,     0,3,reversed = false,None,1666100232908L,        0,roadName,None,None,None,None,None),
        ProjectLink(1021,roadPart,Track.LeftSide, Discontinuity.Continuous,      0,   0,   0,   0,None,None,Some(createdBy),"52e4a2d9-caba-4eae-ba26-da38fc099a2c:1",  0.0  , 93.042,SideCode.Unknown,          (NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(495522.0,6703770.0,0.0), Point(495500.0,6703861.0,0.0)),projectId,RoadAddressChangeType.New,        AdministrativeClass.State,       FrozenLinkInterface, 93.042,    0,     0,3,reversed = false,None,1666100232908L,        0,roadName,None,None,None,None,None),
        ProjectLink(1023,roadPart,Track.Combined, Discontinuity.Continuous,    162, 315, 162, 315,None,None,Some(createdBy),"80f0c4c2-adb2-439f-8d40-896f80bd1a20:2",  0.0  ,154.662,SideCode.AgainstDigitizing,(NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(496785.0,6703178.0,0.0), Point(496903.0,6703278.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,FrozenLinkInterface,154.662,70627,437330,3,reversed = false,None,1654877322508L,148122025,roadName,None,None,None,None,None),
        ProjectLink(1024,roadPart,Track.Combined, Discontinuity.Continuous,    315, 435, 315, 435,None,None,Some(createdBy),"b7957c83-01b0-46ea-b99b-cd8d24acd9b5:2",  0.0  ,121.975,SideCode.AgainstDigitizing,(NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(496691.0,6703101.0,0.0), Point(496785.0,6703178.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,FrozenLinkInterface,121.975,70627,437331,3,reversed = false,None,1654877322508L,148122025,roadName,None,None,None,None,None),
        ProjectLink(1025,roadPart,Track.Combined, Discontinuity.Continuous,    435, 606, 435, 606,None,None,Some(createdBy),"3d8f5740-6fd7-4f8a-b7a3-eeb797043d66:2",  0.0  ,173.903,SideCode.AgainstDigitizing,(NoCP,           JunctionPointCP),(NoCP,JunctionPointCP           ),List(Point(496556.0,6702991.0,0.0), Point(496691.0,6703101.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,FrozenLinkInterface,173.903,70627,437332,3,reversed = false,None,1654877322508L,148122025,roadName,None,None,None,None,None),
        ProjectLink(1026,roadPart,Track.Combined, Discontinuity.Continuous,    606, 618, 606, 618,None,None,Some(createdBy),"fa046ada-2aba-4b4d-8c49-34f14f6a5fcb:2",  0.0  , 12.372,SideCode.TowardsDigitizing,(JunctionPointCP,NoCP           ),(JunctionPointCP,NoCP           ),List(Point(496556.0,6702991.0,0.0), Point(496548.0,6703000.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,FrozenLinkInterface, 12.372,70627,437333,3,reversed = false,None,1654877322508L,148122025,roadName,None,None,None,None,None),
        ProjectLink(1027,roadPart,Track.Combined, Discontinuity.Continuous,    618, 630, 618, 630,None,None,Some(createdBy),"fe6d12b4-f6a7-4389-9000-7183a218442b:2",  0.0  , 12.426,SideCode.TowardsDigitizing,(NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(496548.0,6703000.0,0.0), Point(496540.0,6703010.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,FrozenLinkInterface, 12.426,70627,437334,3,reversed = false,None,1654877322508L,148122025,roadName,None,None,None,None,None),
        ProjectLink(1028,roadPart,Track.Combined, Discontinuity.Continuous,    630, 729, 630, 729,None,None,Some(createdBy),"5598b209-f0e4-4da7-bdb8-ba2016586b55:2",  0.0  ,100.246,SideCode.TowardsDigitizing,(NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(496540.0,6703010.0,0.0), Point(496476.0,6703087.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,FrozenLinkInterface,100.246,70627,437335,3,reversed = false,None,1654877322508L,148122025,roadName,None,None,None,None,None),
        ProjectLink(1029,roadPart,Track.Combined, Discontinuity.Continuous,    729, 805, 729, 805,None,None,Some(createdBy),"607d6270-360a-4eda-8721-ccb6233296db:2",  0.0  , 77.717,SideCode.TowardsDigitizing,(NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(496476.0,6703087.0,0.0), Point(496426.0,6703146.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,FrozenLinkInterface, 77.717,70627,437336,3,reversed = false,None,1654877322508L,148122025,roadName,None,None,None,None,None),
        ProjectLink(1030,roadPart,Track.Combined, Discontinuity.Continuous,    805, 870, 805, 870,None,None,Some(createdBy),"297bf756-4253-487e-a5e4-9f8b8ba24130:2",  0.0  , 66.324,SideCode.TowardsDigitizing,(NoCP,           JunctionPointCP),(NoCP,           JunctionPointCP),List(Point(496426.0,6703146.0,0.0), Point(496384.0,6703198.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,FrozenLinkInterface, 66.324,70627,437337,3,reversed = false,None,1654877322508L,148122025,roadName,None,None,None,None,None),
        ProjectLink(1031,roadPart,Track.RightSide,Discontinuity.Continuous,    870, 912, 870, 912,None,None,Some(createdBy),"28e1c8e8-4145-49d6-9cfd-d096d75bc027:2",  0.0  , 40.83 ,SideCode.AgainstDigitizing,(JunctionPointCP,NoCP           ),(JunctionPointCP,NoCP           ),List(Point(496345.0,6703187.0,0.0), Point(496384.0,6703198.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,FrozenLinkInterface, 40.83 ,67591,425133,3,reversed = false,None,1654877322508L,126326211,roadName,None,None,None,None,None),
        ProjectLink(1033,roadPart,Track.LeftSide, Discontinuity.Continuous,    870, 998, 870, 998,None,None,Some(createdBy),"1fd7845d-b2da-4bf3-ae66-bc263c028db2:2",  0.0  ,128.297,SideCode.AgainstDigitizing,(JunctionPointCP,JunctionPointCP),(JunctionPointCP,JunctionPointCP),List(Point(496274.0,6703139.0,0.0), Point(496384.0,6703198.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,FrozenLinkInterface,128.297,73459,440962,3,reversed = false,None,1654877322508L,148127804,roadName,None,None,None,None,None),
        ProjectLink(1032,roadPart,Track.RightSide,Discontinuity.Continuous,    912, 962, 912, 962,None,None,Some(createdBy),"d053cb3c-14ab-48b0-844e-b8f17d721f01:2",  0.0  , 49.204,SideCode.AgainstDigitizing,(NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(496298.0,6703174.0,0.0), Point(496345.0,6703187.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,FrozenLinkInterface, 49.204,67591,425134,3,reversed = false,None,1654877322508L,126326211,roadName,None,None,None,None,None),
        ProjectLink(1034,roadPart,Track.RightSide,Discontinuity.Continuous,    962,1004, 962,1004,None,None,Some(createdBy),"12c669f2-d7a5-48f4-8d17-5b7558ec1041:2",  0.0  , 40.58 ,SideCode.AgainstDigitizing,(NoCP,           JunctionPointCP),(NoCP,           JunctionPointCP),List(Point(496266.0,6703148.0,0.0), Point(496298.0,6703174.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,FrozenLinkInterface, 40.58 ,67591,425135,3,reversed = false,None,1654877322508L,126326211,roadName,None,None,None,None,None),
        ProjectLink(1035,roadPart,Track.LeftSide, Discontinuity.Continuous,    998,1268, 998,1268,None,None,Some(createdBy),"b9eb619d-f01d-4136-aae9-3c815fc3d435:2",  0.0  ,268.864,SideCode.AgainstDigitizing,(JunctionPointCP,JunctionPointCP),(JunctionPointCP,JunctionPointCP),List(Point(496068.0,6702967.0,0.0), Point(496274.0,6703139.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,FrozenLinkInterface,268.864,73459,440963,3,reversed = false,None,1654877322508L,148127804,roadName,None,None,None,None,None),
        ProjectLink(1036,roadPart,Track.RightSide,Discontinuity.Continuous,   1004,1279,1004,1279,None,None,Some(createdBy),"75b8dff8-87e7-4f78-b801-dce40e5f2780:2",  0.0  ,269.178,SideCode.AgainstDigitizing,(JunctionPointCP,JunctionPointCP),(JunctionPointCP,JunctionPointCP),List(Point(496058.0,6702977.0,0.0), Point(496266.0,6703148.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,FrozenLinkInterface,269.178,67591,425136,3,reversed = false,None,1654877322508L,126326211,roadName,None,None,None,None,None),
        ProjectLink(1037,roadPart,Track.LeftSide, Discontinuity.Continuous,   1268,1312,1268,1312,None,None,Some(createdBy),"a2b87af8-0e10-41d1-a875-3b4722530bf0:1",  0.0  , 44.373,SideCode.AgainstDigitizing,(JunctionPointCP,JunctionPointCP),(JunctionPointCP,JunctionPointCP),List(Point(496033.0,6702939.0,0.0), Point(496068.0,6702967.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,FrozenLinkInterface, 44.373,73459,440964,3,reversed = false,None,1667918043122L,148127804,roadName,None,None,None,None,None),
        ProjectLink(1038,roadPart,Track.RightSide,Discontinuity.Continuous,   1279,1312,1279,1312,None,None,Some(createdBy),"d85337f6-bc77-403f-bb32-9f61732447e9:2",  0.0  , 31.593,SideCode.AgainstDigitizing,(JunctionPointCP,JunctionPointCP),(JunctionPointCP,JunctionPointCP),List(Point(496036.0,6702955.0,0.0), Point(496058.0,6702977.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,FrozenLinkInterface, 31.593,67591,425137,3,reversed = false,None,1654877322508L,126326211,roadName,None,None,None,None,None),
        ProjectLink(1039,roadPart,Track.RightSide,Discontinuity.Continuous,   1312,1477,1312,1477,None,None,Some(createdBy),"3e88e56b-ddde-4273-a159-3ccfb16e28b7:2",  0.0  ,162.616,SideCode.AgainstDigitizing,(JunctionPointCP,NoCP           ),(JunctionPointCP,NoCP           ),List(Point(495887.0,6702920.0,0.0), Point(496036.0,6702955.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.State,       FrozenLinkInterface,162.616,67741,425776,3,reversed = false,None,1654877322508L,126326240,roadName,None,None,None,None,None),
        ProjectLink(1040,roadPart,Track.LeftSide, Discontinuity.Continuous,   1312,1479,1312,1479,None,None,Some(createdBy),"4cb5392d-84b5-417f-b319-24e6750c870a:2",  0.0  ,167.047,SideCode.AgainstDigitizing,(JunctionPointCP,NoCP           ),(JunctionPointCP,NoCP           ),List(Point(495882.0,6702904.0,0.0), Point(496033.0,6702939.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.State,       FrozenLinkInterface,167.047,67703,424458,3,reversed = false,None,1654877322508L,126326358,roadName,None,None,None,None,None),
        ProjectLink(1041,roadPart,Track.RightSide,Discontinuity.Continuous,   1477,1583,1477,1583,None,None,Some(createdBy),"8d1eab7f-f60e-4e2c-94af-c89943e38bf1:2",  0.0  ,103.745,SideCode.TowardsDigitizing,(NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(495887.0,6702920.0,0.0), Point(495794.0,6702964.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.State,       FrozenLinkInterface,103.745,67741,425777,3,reversed = false,None,1654877322508L,126326240,roadName,None,None,None,None,None),
        ProjectLink(1042,roadPart,Track.LeftSide, Discontinuity.Continuous,   1479,1583,1479,1583,None,None,Some(createdBy),"7b624d66-927e-49e8-a7f7-756ee0c48c56:2",  0.0  ,104.301,SideCode.TowardsDigitizing,(NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(495882.0,6702904.0,0.0), Point(495790.0,6702953.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.State,       FrozenLinkInterface,104.301,67703,424459,3,reversed = false,None,1654877322508L,126326358,roadName,None,None,None,None,None),
        ProjectLink(1043,roadPart,Track.RightSide,Discontinuity.Continuous,   1583,1703,1583,1703,None,None,Some(createdBy),"8d1eab7f-f60e-4e2c-94af-c89943e38bf1:2",103.745,221.192,SideCode.TowardsDigitizing,(NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(495794.0,6702964.0,0.0), Point(495712.0,6703047.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.State,       FrozenLinkInterface,117.447,67649,426064,3,reversed = false,None,1654877322508L,126326474,roadName,None,None,None,None,None),
        ProjectLink(1044,roadPart,Track.LeftSide, Discontinuity.Continuous,   1583,1704,1583,1704,None,None,Some(createdBy),"7b624d66-927e-49e8-a7f7-756ee0c48c56:2",104.301,225.651,SideCode.TowardsDigitizing,(NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(495790.0,6702953.0,0.0), Point(495707.0,6703040.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.State,       FrozenLinkInterface,121.35 ,67670,424857,3,reversed = false,None,1654877322508L,126326660,roadName,None,None,None,None,None),
        ProjectLink(1045,roadPart,Track.RightSide,Discontinuity.Continuous,   1703,1877,1703,1877,None,None,Some(createdBy),"dbff38a7-44c9-4135-b7bf-4b8737e667ee:2",  0.0  ,169.646,SideCode.TowardsDigitizing,(NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(495712.0,6703047.0,0.0), Point(495645.0,6703203.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.State,       FrozenLinkInterface,169.646,67649,426065,3,reversed = false,None,1654877322508L,126326474,roadName,None,None,None,None,None),
        ProjectLink(1046,roadPart,Track.LeftSide, Discontinuity.Continuous,   1704,1878,1704,1878,None,None,Some(createdBy),"47294ae9-e2eb-4d14-8042-c6e6f679804e:2",  0.0  ,173.963,SideCode.TowardsDigitizing,(NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(495707.0,6703040.0,0.0), Point(495635.0,6703198.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.State,       FrozenLinkInterface,173.963,67670,424858,3,reversed = false,None,1654877322508L,126326660,roadName,None,None,None,None,None),
        ProjectLink(1047,roadPart,Track.RightSide,Discontinuity.Continuous,   1877,1923,1877,1923,None,None,Some(createdBy),"0c6db46a-258f-4c1d-bcce-50bb00f32c45:2",  0.0  , 45.127,SideCode.TowardsDigitizing,(NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(495645.0,6703203.0,0.0), Point(495633.0,6703246.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.State,       FrozenLinkInterface, 45.127,67649,426066,3,reversed = false,None,1654877322508L,126326474,roadName,None,None,None,None,None),
        ProjectLink(1048,roadPart,Track.LeftSide, Discontinuity.Continuous,   1878,1926,1878,1926,None,None,Some(createdBy),"001467a1-3699-4112-90f3-c65677914cc1:2",  0.0  , 47.648,SideCode.TowardsDigitizing,(NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(495635.0,6703198.0,0.0), Point(495623.0,6703244.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.State,       FrozenLinkInterface, 47.648,67670,424859,3,reversed = false,None,1654877322508L,126326660,roadName,None,None,None,None,None),
        ProjectLink(1049,roadPart,Track.RightSide,Discontinuity.Continuous,   1923,1979,1923,1979,None,None,Some(createdBy),"6b76faf6-b043-461d-9480-369c307b6cfb:1",  0.0  , 54.357,SideCode.TowardsDigitizing,(NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(495633.0,6703246.0,0.0), Point(495621.0,6703299.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.State,       FrozenLinkInterface, 54.357,67649,426067,3,reversed = false,None,1666100232908L,126326474,roadName,None,None,None,None,None),
        ProjectLink(1051,roadPart,Track.LeftSide, Discontinuity.Continuous,   1926,1993,1926,1993,None,None,Some(createdBy),"6622d1b6-f1b4-41ac-a227-811afe5c6a9a:1",  0.0  , 66.659,SideCode.TowardsDigitizing,(NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(495623.0,6703244.0,0.0), Point(495610.0,6703309.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.State,       FrozenLinkInterface, 66.659,67670,424860,3,reversed = false,None,1666100232908L,126326660,roadName,None,None,None,None,None),
        ProjectLink(1050,roadPart,Track.RightSide,Discontinuity.Continuous,   1979,1989,1979,1989,None,None,Some(createdBy),"896f5542-3a9b-4f59-96c1-6617098e1de4:1",  0.0  ,  9.331,SideCode.TowardsDigitizing,(NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(495621.0,6703299.0,0.0), Point(495619.0,6703308.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.State,       FrozenLinkInterface,  9.331,67649,426068,3,reversed = false,None,1666100232908L,126326474,roadName,None,None,None,None,None),
        ProjectLink(1053,roadPart,Track.RightSide,Discontinuity.Continuous,   1989,2046,1989,2046,None,None,Some(createdBy),"4a0bbc4d-2fd8-42d0-bd24-c0029b34946f:1",  0.0  , 56.393,SideCode.TowardsDigitizing,(NoCP,           JunctionPointCP),(NoCP,           JunctionPointCP),List(Point(495619.0,6703308.0,0.0), Point(495607.0,6703363.0,0.0)),projectId,RoadAddressChangeType.Termination,AdministrativeClass.State,       FrozenLinkInterface, 56.393,67649,426069,3,reversed = false,None,1666100232908L,126326474,roadName,None,None,None,None,None),
        ProjectLink(1052,roadPart,Track.LeftSide, Discontinuity.Continuous,   1993,2046,1993,2046,None,None,Some(createdBy),"8babd6ef-1718-4a17-bc21-4743d14b37ab:1",  0.0  , 53.954,SideCode.TowardsDigitizing,(NoCP,           JunctionPointCP),(NoCP,           JunctionPointCP),List(Point(495610.0,6703309.0,0.0), Point(495607.0,6703363.0,0.0)),projectId,RoadAddressChangeType.Termination,AdministrativeClass.State,       FrozenLinkInterface, 53.954,67670,424861,3,reversed = false,None,1666100232908L,126326660,roadName,None,None,None,None,None),
        ProjectLink(1054,roadPart,Track.Combined, Discontinuity.Continuous,   2046,2081,2046,2081,None,None,Some(createdBy),"252f9e1c-6613-41a3-97aa-273b988b1308:2",  0.0  , 35.745,SideCode.TowardsDigitizing,(JunctionPointCP,JunctionPointCP),(JunctionPointCP,JunctionPointCP),List(Point(495607.0,6703363.0,0.0), Point(495602.0,6703399.0,0.0)),projectId,RoadAddressChangeType.Termination,AdministrativeClass.State,       FrozenLinkInterface, 35.745,72801,442640,3,reversed = false,None,1654877322508L,148125286,roadName,None,None,None,None,None),
        ProjectLink(1055,roadPart,Track.Combined, Discontinuity.Continuous,   2081,2091,2081,2091,None,None,Some(createdBy),"a96b72ab-c19c-4845-805b-a44925b90c10:2",  0.0  , 10.732,SideCode.TowardsDigitizing,(JunctionPointCP,NoCP           ),(JunctionPointCP,NoCP           ),List(Point(495602.0,6703399.0,0.0), Point(495601.0,6703409.0,0.0)),projectId,RoadAddressChangeType.Termination,AdministrativeClass.State,       FrozenLinkInterface, 10.732,72801,442641,3,reversed = false,None,1654877322508L,148125286,roadName,None,None,None,None,None),
        ProjectLink(1056,roadPart,Track.Combined, Discontinuity.Continuous,   2091,2167,2091,2167,None,None,Some(createdBy),"40504162-2480-4862-85e9-db63b29e6a86:2",  0.0  , 77.53 ,SideCode.TowardsDigitizing,(NoCP,           JunctionPointCP),(NoCP,           JunctionPointCP),List(Point(495601.0,6703409.0,0.0), Point(495589.0,6703486.0,0.0)),projectId,RoadAddressChangeType.Termination,AdministrativeClass.State,       FrozenLinkInterface, 77.53 ,72801,442642,3,reversed = false,None,1654877322508L,148125286,roadName,None,None,None,None,None),
        ProjectLink(1057,roadPart,Track.Combined, Discontinuity.Continuous,   2167,2436,2167,2436,None,None,Some(createdBy),"4c3f70b5-0654-4941-bb92-ba1259c31c0c:2",  0.0  ,272.462,SideCode.TowardsDigitizing,(JunctionPointCP,NoCP           ),(JunctionPointCP,NoCP           ),List(Point(495589.0,6703486.0,0.0), Point(495534.0,6703753.0,0.0)),projectId,RoadAddressChangeType.Termination,AdministrativeClass.State,       FrozenLinkInterface,272.462,72801,442643,3,reversed = false,None,1654877322508L,148125286,roadName,None,None,None,None,None),
        ProjectLink(1058,roadPart,Track.Combined, Discontinuity.Continuous,   2436,2461,2436,2461,None,None,Some(createdBy),"42944140-d2e5-422f-ada8-259129daccb6:2",  0.0  , 25.443,SideCode.TowardsDigitizing,(NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(495534.0,6703753.0,0.0), Point(495529.0,6703778.0,0.0)),projectId,RoadAddressChangeType.Termination,AdministrativeClass.State,       FrozenLinkInterface, 25.443,72801,442644,3,reversed = false,None,1654877322508L,148125286,roadName,None,None,None,None,None),
        ProjectLink(1059,roadPart,Track.Combined, Discontinuity.Continuous,   2461,2664,2461,2664,None,None,Some(createdBy),"b889b0f7-0573-41e7-bf61-e7a50081f4ec:3",  0.0  ,204.611,SideCode.TowardsDigitizing,(NoCP,           JunctionPointCP),(NoCP,           JunctionPointCP),List(Point(495529.0,6703778.0,0.0), Point(495492.0,6703979.0,0.0)),projectId,RoadAddressChangeType.Termination,AdministrativeClass.State,       FrozenLinkInterface,204.611,72801,442645,3,reversed = false,None,1666100232908L,148125286,roadName,None,None,None,None,None),
        ProjectLink(1060,roadPart,Track.RightSide,Discontinuity.Continuous,   2664,2832,2664,2832,None,None,Some(createdBy),"68d45749-636f-40fb-868d-a79497a11bf1:2",  0.0  ,166.07 ,SideCode.TowardsDigitizing,(JunctionPointCP,JunctionPointCP),(JunctionPointCP,JunctionPointCP),List(Point(495492.0,6703979.0,0.0), Point(495484.0,6704145.0,0.0)),projectId,RoadAddressChangeType.Termination,AdministrativeClass.State,       FrozenLinkInterface,166.07 ,72751,442922,3,reversed = false,None,1654877322508L,148125011,roadName,None,None,None,None,None),
        ProjectLink(1061,roadPart,Track.LeftSide, Discontinuity.Continuous,   2664,2834,2664,2834,None,None,Some(createdBy),"87f48b25-c381-4e5d-bf04-776c24fb9ce4:2",  0.0  ,167.628,SideCode.TowardsDigitizing,(JunctionPointCP,JunctionPointCP),(JunctionPointCP,JunctionPointCP),List(Point(495492.0,6703979.0,0.0), Point(495475.0,6704145.0,0.0)),projectId,RoadAddressChangeType.Termination,AdministrativeClass.State,       FrozenLinkInterface,167.628,73006,441050,3,reversed = false,None,1654877322508L,148127940,roadName,None,None,None,None,None),
        ProjectLink(1063,roadPart,Track.RightSide,Discontinuity.Continuous,   2832,3010,2832,3010,None,None,Some(createdBy),"c728017a-bddf-4c60-b6f8-e9524192cf8d:3",  0.0  ,175.736,SideCode.TowardsDigitizing,(JunctionPointCP,NoCP           ),(JunctionPointCP,NoCP           ),List(Point(495484.0,6704145.0,0.0), Point(495479.0,6704320.0,0.0)),projectId,RoadAddressChangeType.Termination,AdministrativeClass.State,       FrozenLinkInterface,175.736,72751,442923,3,reversed = false,None,1663680838397L,148125011,roadName,None,None,None,None,None),
        ProjectLink(1062,roadPart,Track.LeftSide, Discontinuity.Continuous,   2834,2844,2834,2844,None,None,Some(createdBy),"47806aa6-de17-4e37-9636-8e37a8ff949c:3",  0.0  , 10.663,SideCode.TowardsDigitizing,(JunctionPointCP,JunctionPointCP),(JunctionPointCP,JunctionPointCP),List(Point(495475.0,6704145.0,0.0), Point(495474.0,6704156.0,0.0)),projectId,RoadAddressChangeType.Termination,AdministrativeClass.State,       FrozenLinkInterface, 10.663,73006,441051,3,reversed = false,None,1663680838397L,148127940,roadName,None,None,None,None,None),
        ProjectLink(1064,roadPart,Track.LeftSide, Discontinuity.Continuous,   2844,3017,2844,3017,None,None,Some(createdBy),"6c9bdf9e-a570-476d-9cae-6244c91b23eb:3",  0.0  ,169.901,SideCode.TowardsDigitizing,(JunctionPointCP,NoCP           ),(JunctionPointCP,NoCP           ),List(Point(495474.0,6704156.0,0.0), Point(495469.0,6704326.0,0.0)),projectId,RoadAddressChangeType.Termination,AdministrativeClass.State,       FrozenLinkInterface,169.901,73006,441052,3,reversed = false,None,1663680838397L,148127940,roadName,None,None,None,None,None),
        ProjectLink(1065,roadPart,Track.RightSide,Discontinuity.Continuous,   3010,3021,3010,3021,None,None,Some(createdBy),"0cd62548-d5d3-4776-87f8-cf1b36ad0860:3",  0.0  , 11.227,SideCode.TowardsDigitizing,(NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(495479.0,6704320.0,0.0), Point(495478.0,6704331.0,0.0)),projectId,RoadAddressChangeType.Termination,AdministrativeClass.State,       FrozenLinkInterface, 11.227,72751,442924,3,reversed = false,None,1663680838397L,148125011,roadName,None,None,None,None,None),
        ProjectLink(1066,roadPart,Track.LeftSide, Discontinuity.Continuous,   3017,3029,3017,3029,None,None,Some(createdBy),"6870e91e-5616-4f0f-b68c-13155a61e144:3",  0.0  , 12.245,SideCode.TowardsDigitizing,(NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(495469.0,6704326.0,0.0), Point(495468.0,6704338.0,0.0)),projectId,RoadAddressChangeType.Termination,AdministrativeClass.State,       FrozenLinkInterface, 12.245,73006,441053,3,reversed = false,None,1663680838397L,148127940,roadName,None,None,None,None,None),
        ProjectLink(1067,roadPart,Track.RightSide,Discontinuity.Continuous,   3021,3073,3021,3073,None,None,Some(createdBy),"3f5ee8b0-d905-4ef8-8bc7-d9e6de5a4ca8:3",  0.0  , 50.491,SideCode.TowardsDigitizing,(NoCP,           JunctionPointCP),(NoCP,           JunctionPointCP),List(Point(495478.0,6704331.0,0.0), Point(495466.0,6704380.0,0.0)),projectId,RoadAddressChangeType.Termination,AdministrativeClass.State,       FrozenLinkInterface, 50.491,72751,442925,3,reversed = false,None,1663680838397L,148125011,roadName,None,None,None,None,None),
        ProjectLink(1068,roadPart,Track.LeftSide, Discontinuity.Continuous,   3029,3073,3029,3073,None,None,Some(createdBy),"3f8c0751-c97c-4935-bce2-925d423ca3ce:4",  0.0  , 42.233,SideCode.TowardsDigitizing,(NoCP,           JunctionPointCP),(NoCP,           JunctionPointCP),List(Point(495468.0,6704338.0,0.0), Point(495466.0,6704380.0,0.0)),projectId,RoadAddressChangeType.Termination,AdministrativeClass.State,       FrozenLinkInterface, 42.233,73006,441054,3,reversed = false,None,1665149643583L,148127940,roadName,None,None,None,None,None),
        ProjectLink(1069,roadPart,Track.Combined, Discontinuity.Continuous,   3073,3629,3073,3629,None,None,Some(createdBy),"1a8aa2fe-46f6-4e32-a196-f688ae9410cb:3",  0.0  ,554.766,SideCode.TowardsDigitizing,(JunctionPointCP,NoCP           ),(JunctionPointCP,NoCP           ),List(Point(495466.0,6704380.0,0.0), Point(495251.0,6704888.0,0.0)),projectId,RoadAddressChangeType.Termination,AdministrativeClass.State,       FrozenLinkInterface,554.766,73573,438660,3,reversed = false,None,1663680838397L,148124389,roadName,None,None,None,None,None),
        ProjectLink(1070,roadPart,Track.Combined, Discontinuity.Continuous,   3629,3653,3629,3653,None,None,Some(createdBy),"1471a3f5-5e17-4be1-a304-cd34572799f5:3",  0.0  , 24.363,SideCode.TowardsDigitizing,(NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(495251.0,6704888.0,0.0), Point(495240.0,6704910.0,0.0)),projectId,RoadAddressChangeType.Termination,AdministrativeClass.State,       FrozenLinkInterface, 24.363,73573,438661,3,reversed = false,None,1663680838397L,148124389,roadName,None,None,None,None,None),
        ProjectLink(1071,roadPart,Track.Combined, Discontinuity.Continuous,   3653,3839,3653,3839,None,None,Some(createdBy),"b900b92a-959e-4b68-b42a-6385122d8ca6:1",  0.0  ,185.374,SideCode.TowardsDigitizing,(NoCP,           NoCP           ),(NoCP,           NoCP           ),List(Point(495240.0,6704910.0,0.0), Point(495161.0,6705077.0,0.0)),projectId,RoadAddressChangeType.Termination,AdministrativeClass.State,       FrozenLinkInterface,185.374,73573,438662,3,reversed = false,None,1666100232908L,148124389,roadName,None,None,None,None,None),
        ProjectLink(1072,roadPart,Track.Combined, Discontinuity.Discontinuous,3839,4271,3839,4271,None,None,Some(createdBy),"2cad8e8d-42ec-48df-bd1c-b7c26d5f0335:1",  0.0  ,431.081,SideCode.TowardsDigitizing,(NoCP,           JunctionPointCP),(NoCP,           JunctionPointCP),List(Point(495161.0,6705077.0,0.0), Point(494988.0,6705472.0,0.0)),projectId,RoadAddressChangeType.Transfer,   AdministrativeClass.State,       FrozenLinkInterface,431.081,73573,438663,3,reversed = false,None,1666100232908L,148124389,roadName,None,None,None,None,None)
      )

      val roadways = Seq(
        Roadway(70627,148122025,roadPart,AdministrativeClass.Municipality,Track.Combined, Discontinuity.Continuous,      0, 870,reversed = false,DateTime.parse("1989-01-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-08T00:00:00.000+02:00"),None),
        Roadway(67591,126326211,roadPart,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Continuous,    870,1312,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-30T00:00:00.000+03:00"),None),
        Roadway(73459,148127804,roadPart,AdministrativeClass.Municipality,Track.LeftSide, Discontinuity.Continuous,    870,1312,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-08T00:00:00.000+02:00"),None),
        Roadway(67741,126326240,roadPart,AdministrativeClass.State,       Track.RightSide,Discontinuity.Continuous,   1312,1583,reversed = false,DateTime.parse("1974-11-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2015-12-11T00:00:00.000+02:00"),None),
        Roadway(67703,126326358,roadPart,AdministrativeClass.State,       Track.LeftSide, Discontinuity.Continuous,   1312,1583,reversed = false,DateTime.parse("1974-11-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2015-12-11T00:00:00.000+02:00"),None),
        Roadway(67649,126326474,roadPart,AdministrativeClass.State,       Track.RightSide,Discontinuity.Continuous,   1583,2046,reversed = false,DateTime.parse("2002-10-01T00:00:00.000+03:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2015-12-11T00:00:00.000+02:00"),None),
        Roadway(67670,126326660,roadPart,AdministrativeClass.State,       Track.LeftSide, Discontinuity.Continuous,   1583,2046,reversed = false,DateTime.parse("2002-10-01T00:00:00.000+03:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2015-12-11T00:00:00.000+02:00"),None),
        Roadway(72801,148125286,roadPart,AdministrativeClass.State,       Track.Combined, Discontinuity.Continuous,   2046,2664,reversed = false,DateTime.parse("1974-11-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-08T00:00:00.000+02:00"),None),
        Roadway(72751,148125011,roadPart,AdministrativeClass.State,       Track.RightSide,Discontinuity.Continuous,   2664,3073,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-30T00:00:00.000+03:00"),None),
        Roadway(73006,148127940,roadPart,AdministrativeClass.State,       Track.LeftSide, Discontinuity.Continuous,   2664,3073,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-08T00:00:00.000+02:00"),None),
        Roadway(73573,148124389,roadPart,AdministrativeClass.State,       Track.Combined, Discontinuity.Discontinuous,3073,4271,reversed = false,DateTime.parse("1974-11-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-08T00:00:00.000+02:00"),None)
      )

      val linearLocations = Seq(
        LinearLocation(438663,4.0,"2cad8e8d-42ec-48df-bd1c-b7c26d5f0335:1",0.0,431.081,SideCode.TowardsDigitizing,1666100232908L,(CalibrationPointReference(None,None),CalibrationPointReference(Some(4271),Some(JunctionPointCP))),List(Point(495161.085,6705077.795,0.0), Point(494988.765,6705472.854,0.0)),FrozenLinkInterface,148124389,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(438660,1.0,"1a8aa2fe-46f6-4e32-a196-f688ae9410cb:3",0.0,554.766,SideCode.TowardsDigitizing,1663680838397L,(CalibrationPointReference(Some(3073),Some(JunctionPointCP)),CalibrationPointReference(None,None)),List(Point(495466.825,6704380.847,0.0), Point(495251.395,6704888.511,0.0)),FrozenLinkInterface,148124389,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(438661,2.0,"1471a3f5-5e17-4be1-a304-cd34572799f5:3",0.0,24.363,SideCode.TowardsDigitizing,1663680838397L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(495251.395,6704888.511,0.0), Point(495240.442,6704910.273,0.0)),FrozenLinkInterface,148124389,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(438662,3.0,"b900b92a-959e-4b68-b42a-6385122d8ca6:1",0.0,185.374,SideCode.TowardsDigitizing,1666100232908L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(495240.442,6704910.273,0.0), Point(495161.085,6705077.795,0.0)),FrozenLinkInterface,148124389,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(441053,4.0,"6870e91e-5616-4f0f-b68c-13155a61e144:3",0.0,12.245,SideCode.TowardsDigitizing,1663680838397L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(495469.415,6704326.433,0.0), Point(495468.634,6704338.653,0.0)),FrozenLinkInterface,148127940,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(441051,2.0,"47806aa6-de17-4e37-9636-8e37a8ff949c:3",0.0,10.663,SideCode.TowardsDigitizing,1663680838397L,(CalibrationPointReference(Some(2834),Some(JunctionPointCP)),CalibrationPointReference(Some(2844),Some(JunctionPointCP))),List(Point(495475.295,6704145.999,0.0), Point(495474.858,6704156.653,0.0)),FrozenLinkInterface,148127940,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(441054,5.0,"3f8c0751-c97c-4935-bce2-925d423ca3ce:4",0.0,42.233,SideCode.TowardsDigitizing,1665149643583L,(CalibrationPointReference(None,None),CalibrationPointReference(Some(3073),Some(JunctionPointCP))),List(Point(495468.634,6704338.653,0.0), Point(495466.825,6704380.847,0.0)),FrozenLinkInterface,148127940,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(441050,1.0,"87f48b25-c381-4e5d-bf04-776c24fb9ce4:2",0.0,167.628,SideCode.TowardsDigitizing,1654877322508L,(CalibrationPointReference(Some(2664),Some(JunctionPointCP)),CalibrationPointReference(Some(2834),Some(JunctionPointCP))),List(Point(495492.914,6703979.466,0.0), Point(495475.295,6704145.999,0.0)),FrozenLinkInterface,148127940,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(441052,3.0,"6c9bdf9e-a570-476d-9cae-6244c91b23eb:3",0.0,169.901,SideCode.TowardsDigitizing,1663680838397L,(CalibrationPointReference(Some(2844),Some(JunctionPointCP)),CalibrationPointReference(None,None)),List(Point(495474.858,6704156.653,0.0), Point(495469.415,6704326.433,0.0)),FrozenLinkInterface,148127940,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(442925,4.0,"3f5ee8b0-d905-4ef8-8bc7-d9e6de5a4ca8:3",0.0,50.491,SideCode.TowardsDigitizing,1663680838397L,(CalibrationPointReference(None,None),CalibrationPointReference(Some(3073),Some(JunctionPointCP))),List(Point(495478.514,6704331.893,0.0), Point(495466.825,6704380.847,0.0)),FrozenLinkInterface,148125011,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(442923,2.0,"c728017a-bddf-4c60-b6f8-e9524192cf8d:3",0.0,175.736,SideCode.TowardsDigitizing,1663680838397L,(CalibrationPointReference(Some(2832),Some(JunctionPointCP)),CalibrationPointReference(None,None)),List(Point(495484.602,6704145.105,0.0), Point(495479.555,6704320.714,0.0)),FrozenLinkInterface,148125011,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(442922, 1.0, "68d45749-636f-40fb-868d-a79497a11bf1:2", 0.0, 166.07, SideCode.TowardsDigitizing, 1654877322508L, (CalibrationPointReference(Some(2664), Some(JunctionPointCP)), CalibrationPointReference(Some(2832), Some(JunctionPointCP))), List(Point(495492.914, 6703979.466, 0.0), Point(495484.602, 6704145.105, 0.0)), FrozenLinkInterface, 148125011, Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")), None),
        LinearLocation(442924, 3.0, "0cd62548-d5d3-4776-87f8-cf1b36ad0860:3", 0.0, 11.227, SideCode.TowardsDigitizing, 1663680838397L, (CalibrationPointReference(None, None), CalibrationPointReference(None, None)), List(Point(495479.555, 6704320.714, 0.0), Point(495478.514, 6704331.893, 0.0)), FrozenLinkInterface, 148125011, Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")), None),
        LinearLocation(442641,2.0,"a96b72ab-c19c-4845-805b-a44925b90c10:2",0.0,10.732,SideCode.TowardsDigitizing,1654877322508L,(CalibrationPointReference(Some(2081),Some(JunctionPointCP)),CalibrationPointReference(None,None)),List(Point(495602.723,6703399.169,0.0), Point(495601.293,6703409.805,0.0)),FrozenLinkInterface,148125286,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(442644,5.0,"42944140-d2e5-422f-ada8-259129daccb6:2",0.0,25.443,SideCode.TowardsDigitizing,1654877322508L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(495534.485,6703753.234,0.0), Point(495529.164,6703778.114,0.0)),FrozenLinkInterface,148125286,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(442645,6.0,"b889b0f7-0573-41e7-bf61-e7a50081f4ec:3",0.0,204.611,SideCode.TowardsDigitizing,1666100232908L,(CalibrationPointReference(None,None),CalibrationPointReference(Some(2664),Some(JunctionPointCP))),List(Point(495529.164,6703778.114,0.0), Point(495492.914,6703979.466,0.0)),FrozenLinkInterface,148125286,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(442640, 1.0, "252f9e1c-6613-41a3-97aa-273b988b1308:2", 0.0, 35.745, SideCode.TowardsDigitizing, 1654877322508L, (CalibrationPointReference(Some(2046), Some(JunctionPointCP)), CalibrationPointReference(Some(2081), Some(JunctionPointCP))), List(Point(495607.49, 6703363.743, 0.0), Point(495602.723, 6703399.169, 0.0)), FrozenLinkInterface, 148125286, Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")), None),
        LinearLocation(442643, 4.0, "4c3f70b5-0654-4941-bb92-ba1259c31c0c:2", 0.0, 272.462, SideCode.TowardsDigitizing, 1654877322508L, (CalibrationPointReference(Some(2167), Some(JunctionPointCP)), CalibrationPointReference(None, None)), List(Point(495589.182, 6703486.383, 0.0), Point(495534.485, 6703753.234, 0.0)), FrozenLinkInterface, 148125286, Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")), None),
        LinearLocation(442642,3.0,"40504162-2480-4862-85e9-db63b29e6a86:2",0.0,77.53,SideCode.TowardsDigitizing,1654877322508L,(CalibrationPointReference(None,None),CalibrationPointReference(Some(2167),Some(JunctionPointCP))),List(Point(495601.293,6703409.805,0.0), Point(495589.182,6703486.383,0.0)),FrozenLinkInterface,148125286,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(424859,3.0,"001467a1-3699-4112-90f3-c65677914cc1:2",0.0,47.648,SideCode.TowardsDigitizing,1654877322508L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(495635.165,6703198.368,0.0), Point(495623.028,6703244.443,0.0)),FrozenLinkInterface,126326660,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(424858,2.0,"47294ae9-e2eb-4d14-8042-c6e6f679804e:2",0.0,173.963,SideCode.TowardsDigitizing,1654877322508L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(495707.058,6703040.698,0.0), Point(495635.165,6703198.368,0.0)),FrozenLinkInterface,126326660,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(424860,4.0,"6622d1b6-f1b4-41ac-a227-811afe5c6a9a:1",0.0,66.659,SideCode.TowardsDigitizing,1666100232908L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(495623.028,6703244.443,0.0), Point(495610.425,6703309.893,0.0)),FrozenLinkInterface,126326660,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(424857,1.0,"7b624d66-927e-49e8-a7f7-756ee0c48c56:2",104.301,225.651,SideCode.TowardsDigitizing,1654877322508L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(495790.942,6702953.463,0.0), Point(495707.058,6703040.698,0.0)),FrozenLinkInterface,126326660,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(424861,5.0,"8babd6ef-1718-4a17-bc21-4743d14b37ab:1",0.0,53.954,SideCode.TowardsDigitizing,1666100232908L,(CalibrationPointReference(None,None),CalibrationPointReference(Some(2046),Some(JunctionPointCP))),List(Point(495610.425,6703309.893,0.0), Point(495607.49,6703363.743,0.0)),FrozenLinkInterface,126326660,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(426066,3.0,"0c6db46a-258f-4c1d-bcce-50bb00f32c45:2",0.0,45.127,SideCode.TowardsDigitizing,1654877322508L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(495645.504,6703203.088,0.0), Point(495633.142,6703246.466,0.0)),FrozenLinkInterface,126326474,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(426064,1.0,"8d1eab7f-f60e-4e2c-94af-c89943e38bf1:2",103.745,221.192,SideCode.TowardsDigitizing,1654877322508L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(495794.581,6702964.615,0.0), Point(495712.07,6703047.657,0.0)),FrozenLinkInterface,126326474,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(426065,2.0,"dbff38a7-44c9-4135-b7bf-4b8737e667ee:2",0.0,169.646,SideCode.TowardsDigitizing,1654877322508L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(495712.07,6703047.657,0.0), Point(495645.504,6703203.088,0.0)),FrozenLinkInterface,126326474,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(426067,4.0,"6b76faf6-b043-461d-9480-369c307b6cfb:1",0.0,54.357,SideCode.TowardsDigitizing,1666100232908L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(495633.142,6703246.466,0.0), Point(495621.143,6703299.456,0.0)),FrozenLinkInterface,126326474,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(426069,6.0,"4a0bbc4d-2fd8-42d0-bd24-c0029b34946f:1",0.0,56.393,SideCode.TowardsDigitizing,1666100232908L,(CalibrationPointReference(None,None),CalibrationPointReference(Some(2046),Some(JunctionPointCP))),List(Point(495619.393,6703308.621,0.0), Point(495607.49,6703363.743,0.0)),FrozenLinkInterface,126326474,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(426068,5.0,"896f5542-3a9b-4f59-96c1-6617098e1de4:1",0.0,9.331,SideCode.TowardsDigitizing,1666100232908L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(495621.143,6703299.456,0.0), Point(495619.393,6703308.621,0.0)),FrozenLinkInterface,126326474,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(424459,2.0,"7b624d66-927e-49e8-a7f7-756ee0c48c56:2",0.0,104.301,SideCode.TowardsDigitizing,1654877322508L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(495882.575,6702904.459,0.0), Point(495790.942,6702953.463,0.0)),FrozenLinkInterface,126326358,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(424458,1.0,"4cb5392d-84b5-417f-b319-24e6750c870a:2",0.0,167.047,SideCode.AgainstDigitizing,1654877322508L,(CalibrationPointReference(Some(1312),Some(JunctionPointCP)),CalibrationPointReference(None,None)),List(Point(495882.575,6702904.459,0.0), Point(496033.917,6702939.178,0.0)),FrozenLinkInterface,126326358,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(440963,2.0,"b9eb619d-f01d-4136-aae9-3c815fc3d435:2",0.0,268.864,SideCode.AgainstDigitizing,1654877322508L,(CalibrationPointReference(Some(998),Some(JunctionPointCP)),CalibrationPointReference(Some(1268),Some(JunctionPointCP))),List(Point(496068.023,6702967.563,0.0), Point(496274.58,6703139.631,0.0)),FrozenLinkInterface,148127804,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(440964,3.0,"a2b87af8-0e10-41d1-a875-3b4722530bf0:1",0.0,44.373,SideCode.AgainstDigitizing,1667918043122L,(CalibrationPointReference(Some(1268),Some(JunctionPointCP)),CalibrationPointReference(Some(1312),Some(JunctionPointCP))),List(Point(496033.917,6702939.178,0.0), Point(496068.023,6702967.563,0.0)),FrozenLinkInterface,148127804,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(440962,1.0,"1fd7845d-b2da-4bf3-ae66-bc263c028db2:2",0.0,128.297,SideCode.AgainstDigitizing,1654877322508L,(CalibrationPointReference(Some(870),Some(JunctionPointCP)),CalibrationPointReference(Some(998),Some(JunctionPointCP))),List(Point(496274.58,6703139.631,0.0), Point(496384.957,6703198.176,0.0)),FrozenLinkInterface,148127804,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(425134,2.0,"d053cb3c-14ab-48b0-844e-b8f17d721f01:2",0.0,49.204,SideCode.AgainstDigitizing,1654877322508L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(496298.183,6703174.487,0.0), Point(496345.528,6703187.611,0.0)),FrozenLinkInterface,126326211,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(425135,3.0,"12c669f2-d7a5-48f4-8d17-5b7558ec1041:2",0.0,40.58,SideCode.AgainstDigitizing,1654877322508L,(CalibrationPointReference(None,None),CalibrationPointReference(Some(1004),Some(JunctionPointCP))),List(Point(496266.909,6703148.69,0.0), Point(496298.183,6703174.487,0.0)),FrozenLinkInterface,126326211,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(425137,5.0,"d85337f6-bc77-403f-bb32-9f61732447e9:2",0.0,31.593,SideCode.AgainstDigitizing,1654877322508L,(CalibrationPointReference(Some(1279),Some(JunctionPointCP)),CalibrationPointReference(Some(1312),Some(JunctionPointCP))),List(Point(496036.313,6702955.816,0.0), Point(496058.988,6702977.813,0.0)),FrozenLinkInterface,126326211,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(425133,1.0,"28e1c8e8-4145-49d6-9cfd-d096d75bc027:2",0.0,40.83,SideCode.AgainstDigitizing,1654877322508L,(CalibrationPointReference(Some(870),Some(JunctionPointCP)),CalibrationPointReference(None,None)),List(Point(496345.528,6703187.611,0.0), Point(496384.957,6703198.176,0.0)),FrozenLinkInterface,126326211,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(425136,4.0,"75b8dff8-87e7-4f78-b801-dce40e5f2780:2",0.0,269.178,SideCode.AgainstDigitizing,1654877322508L,(CalibrationPointReference(Some(1004),Some(JunctionPointCP)),CalibrationPointReference(Some(1279),Some(JunctionPointCP))),List(Point(496058.988,6702977.813,0.0), Point(496266.909,6703148.69,0.0)),FrozenLinkInterface,126326211,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(437329,1.0,"004caef8-4af1-43a9-aa5e-c76d1ccee9ee:2",0.0,164.967,SideCode.AgainstDigitizing,1654877322508L,(CalibrationPointReference(Some(0),Some(JunctionPointCP)),CalibrationPointReference(None,None)),List(Point(496903.986,6703278.2,0.0), Point(497030.595,6703383.956,0.0)),FrozenLinkInterface,148122025,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(437334,6.0,"fe6d12b4-f6a7-4389-9000-7183a218442b:2",0.0,12.426,SideCode.TowardsDigitizing,1654877322508L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(496548.812,6703000.638,0.0), Point(496540.846,6703010.175,0.0)),FrozenLinkInterface,148122025,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(437333,5.0,"fa046ada-2aba-4b4d-8c49-34f14f6a5fcb:2",0.0,12.372,SideCode.TowardsDigitizing,1654877322508L,(CalibrationPointReference(Some(606),Some(JunctionPointCP)),CalibrationPointReference(None,None)),List(Point(496556.743,6702991.143,0.0), Point(496548.812,6703000.638,0.0)),FrozenLinkInterface,148122025,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(437331,3.0,"b7957c83-01b0-46ea-b99b-cd8d24acd9b5:2",0.0,121.975,SideCode.AgainstDigitizing,1654877322508L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(496691.241,6703101.381,0.0), Point(496785.578,6703178.702,0.0)),FrozenLinkInterface,148122025,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(437337,9.0,"297bf756-4253-487e-a5e4-9f8b8ba24130:2",0.0,66.324,SideCode.TowardsDigitizing,1654877322508L,(CalibrationPointReference(None,None),CalibrationPointReference(Some(870),Some(JunctionPointCP))),List(Point(496426.179,6703146.22,0.0), Point(496384.957,6703198.176,0.0)),FrozenLinkInterface,148122025,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(437332,4.0,"3d8f5740-6fd7-4f8a-b7a3-eeb797043d66:2",0.0,173.903,SideCode.AgainstDigitizing,1654877322508L,(CalibrationPointReference(None,None),CalibrationPointReference(Some(606),Some(JunctionPointCP))),List(Point(496556.743,6702991.143,0.0), Point(496691.241,6703101.381,0.0)),FrozenLinkInterface,148122025,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(437330,2.0,"80f0c4c2-adb2-439f-8d40-896f80bd1a20:2",0.0,154.662,SideCode.AgainstDigitizing,1654877322508L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(496785.578,6703178.702,0.0), Point(496903.986,6703278.2,0.0)),FrozenLinkInterface,148122025,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(437336,8.0,"607d6270-360a-4eda-8721-ccb6233296db:2",0.0,77.717,SideCode.TowardsDigitizing,1654877322508L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(496476.718,6703087.226,0.0), Point(496426.179,6703146.22,0.0)),FrozenLinkInterface,148122025,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(437335,7.0,"5598b209-f0e4-4da7-bdb8-ba2016586b55:2",0.0,100.246,SideCode.TowardsDigitizing,1654877322508L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(496540.846,6703010.175,0.0), Point(496476.718,6703087.226,0.0)),FrozenLinkInterface,148122025,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(425777,2.0,"8d1eab7f-f60e-4e2c-94af-c89943e38bf1:2",0.0,103.745,SideCode.TowardsDigitizing,1654877322508L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(495887.781,6702920.186,0.0), Point(495794.581,6702964.615,0.0)),FrozenLinkInterface,126326240,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None),
        LinearLocation(425776,1.0,"3e88e56b-ddde-4273-a159-3ccfb16e28b7:2",0.0,162.616,SideCode.AgainstDigitizing,1654877322508L,(CalibrationPointReference(Some(1312),Some(JunctionPointCP)),CalibrationPointReference(None,None)),List(Point(495887.781,6702920.186,0.0), Point(496036.313,6702955.816,0.0)),FrozenLinkInterface,126326240,Some(DateTime.parse("2021-11-26T00:00:00.000+02:00")),None)
      )

      projectDAO.create(Project(projectId, ProjectState.Incomplete, "f", createdBy, DateTime.now(), "", DateTime.now(), DateTime.now(), "", Seq(), Seq(), None, None))
      projectReservedPartDAO.reserveRoadPart(projectId, roadPart, createdBy)
      roadwayDAO.create(roadways)
      linearLocationDAO.create(linearLocations)
      projectLinkDAO.create(projectLinks)

      val (newLinks, oldLinks) = projectLinks.filterNot(_.status == RoadAddressChangeType.Termination).partition(_.status == RoadAddressChangeType.New)
      val projectLinksWithAssignedValues = defaultSectionCalculatorStrategy.assignMValues(newLinks, oldLinks, Seq.empty[UserDefinedCalibrationPoint])
      // All Should have addresses
      projectLinksWithAssignedValues.forall(!_.isNotCalculated) should be(true)
      // Check continuity of address and geometry
      val pls = projectLinksWithAssignedValues.filter(_.track != Track.RightSide).sortBy(_.startAddrMValue)
      pls.tail.foldLeft(pls.head)((a, b) => {
        a.endPoint.connected(b.startingPoint)  should be(true)
        b
      })
    }
  }

  test("Test defaultSectionCalculatorStrategy.assignMValues() and findStartingPoints When using 4 geometries that end up in a point " +
    "Then return the same project links, but now with correct MValues and directions") {
    runWithRollback {
      val geomLeft1 = Seq(Point(10.0, 10.0), Point(20.0, 10.0))
      val geomLeft2 = Seq(Point(20.0, 10.0), Point(30.0, 10.0))

      val projectLinkLeft1 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12345L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomLeft1, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft1), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkLeft2 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12346L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomLeft2, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft2), 0L, 0, 0, reversed = false, None, 86400L)

      val geomRight1 = Seq(Point(10.0, 20.0), Point(20.0, 20.0))
      val geomRight2 = Seq(Point(20.0, 20.0), Point(30.0, 20.0))

      val projectLinkRight1 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(1), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12347L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomRight1, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight1), 0L, 0, 0, reversed = false, None, 86400L)

      val projectLinkRight2 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(1), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12348L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomRight2, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight2), 0L, 0, 0, reversed = false, None, 86400L)

      val leftSideProjectLinks = Seq(projectLinkLeft1, projectLinkLeft2)
      val rightSideProjectLinks = Seq(projectLinkRight1, projectLinkRight2)
      val newProjectLinks = leftSideProjectLinks ++ rightSideProjectLinks

      val projectLinksWithAssignedValues = defaultSectionCalculatorStrategy.assignMValues(newProjectLinks, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
      val startingPointsForCalculations = defaultSectionCalculatorStrategy.findStartingPoints(newProjectLinks, Seq.empty[ProjectLink], Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
      projectLinksWithAssignedValues.forall(_.sideCode == projectLinksWithAssignedValues.head.sideCode) should be(true)
      startingPointsForCalculations should be((geomRight2.last, geomLeft2.last))

      val additionalGeomLeft1 = Seq(Point(40.0, 10.0), Point(30.0, 10.0))
      val additionalGeomRight1 = Seq(Point(40.0, 20.0), Point(30.0, 20.0))

      val additionalProjectLinkLeft1  = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12349L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), additionalGeomLeft1,  0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(additionalGeomLeft1),  0L, 0, 0, reversed = false, None, 86400L)
      val additionalProjectLinkRight1 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(1), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12350L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), additionalGeomRight1, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(additionalGeomRight1), 0L, 0, 0, reversed = false, None, 86400L)

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

      val additionalProjectLinkLeftBefore  = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12351L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), additionalGeomLeftBefore,  0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(additionalGeomLeftBefore),  0L, 0, 0, reversed = false, None, 86400L)
      val additionalProjectLinkRightBefore = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(1), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12352L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), additionalGeomRightBefore, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(additionalGeomRightBefore), 0L, 0, 0, reversed = false, None, 86400L)

      val leftSideBeforeProjectLinks = Seq(additionalProjectLinkLeftBefore)
      val rightSideBeforeProjectLinks = Seq(additionalProjectLinkRightBefore)
      val beforeProjectLinks = leftSideBeforeProjectLinks ++ rightSideBeforeProjectLinks

      val projectLinksWithAssignedValuesBefore = defaultSectionCalculatorStrategy.assignMValues(projectLinksWithAssignedValues ++ beforeProjectLinks, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
      projectLinksWithAssignedValuesBefore.filter(p => projectLinksWithAssignedValues.map(_.linkId).contains(p.linkId)).forall(_.sideCode == projectLinksWithAssignedValuesBefore.filter(p => projectLinksWithAssignedValues.map(_.linkId).contains(p.linkId)).head.sideCode) should be(true)
      projectLinksWithAssignedValuesBefore.map(_.sideCode.value).containsSlice(projectLinksWithAssignedValuesPlus.filter(p => additionalProjectLinks.map(_.linkId).contains(p.linkId)).map(_.sideCode).map(SideCode.switch).map(_.value))
    }
  }

  test("Test defaultSectionCalculatorStrategy.assignMValues() " +
       "When using 2 tracks with not proper administrative class sections " +
       "Then will fail because the sections cannot be adjusted for two tracks.") {
    runWithRollback {
      val geomLeft1 = Seq(Point(0.0, 0.0), Point(0.0, 30.0))
      val geomLeft2 = Seq(Point(0.0, 30.0), Point(0.0, 60.0))

      val projId = Sequences.nextViiteProjectId
      val roadwayId = Sequences.nextRoadwayId
      val linearLocationId = Sequences.nextLinearLocationId
      val projectLinkId = Sequences.nextProjectLinkId
      val project = Project(projId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val projectLinkLeft1 = ProjectLink(projectLinkId,     RoadPart(9999, 1), Track.apply(2), Discontinuity.Continuous,     0L,  0L, 0L,  0L, None, None, Some("user"), 12345L.toString, 0.0,  0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomLeft1, projId, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft1), 0L, 0, 0, reversed = false, None, 86400L, roadwayNumber = 12345L)
      val projectLinkLeft2 = ProjectLink(projectLinkId + 1, RoadPart(9999, 1), Track.apply(2), Discontinuity.Discontinuous,  0L,  0L, 0L,  0L, None, None, Some("user"), 12346L.toString, 0.0,  0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomLeft2, projId, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft2), 0L, 0, 0, reversed = false, None, 86400L, roadwayNumber = 12345L)

      val geomRight1 = Seq(Point(5.0, 0.0), Point(5.0, 5.0))
      val geomRight2 = Seq(Point(5.0, 5.0), Point(5.0, 62.0))

      val projectLinkRight1 = ProjectLink(projectLinkId + 2, RoadPart(9999, 1), Track.apply(1), Discontinuity.Continuous,    0L,  5L, 0L,  5L, None, None, Some("user"), 12347L.toString, 0.0,  5.0, SideCode.Unknown, (RoadAddressCP, NoCP), (NoCP, NoCP), geomRight1, projId, RoadAddressChangeType.Transfer, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight1), roadwayId,     linearLocationId, 0,     reversed = false, None, 86400L, roadwayNumber = 12346L)
      val projectLinkRight2 = ProjectLink(projectLinkId + 3, RoadPart(9999, 1), Track.apply(1), Discontinuity.Discontinuous, 5L, 62L, 5L, 62L, None, None, Some("user"), 12348L.toString, 0.0, 57.0, SideCode.Unknown, (NoCP, RoadAddressCP), (NoCP, NoCP), geomRight2, projId, RoadAddressChangeType.Transfer, AdministrativeClass.State,        LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight2), roadwayId + 1, linearLocationId + 1, 0, reversed = false, None, 86400L, roadwayNumber = 12347L)

      val leftSideProjectLinks = Seq(projectLinkLeft1, projectLinkLeft2)
      val rightSideProjectLinks = Seq(projectLinkRight1, projectLinkRight2)
      val (linearLocation1, roadway1) = Seq(projectLinkRight1).map(toRoadwayAndLinearLocation).head
      val (linearLocation2, roadway2) = Seq(projectLinkRight2).map(toRoadwayAndLinearLocation).head

      buildTestDataForProject(Some(project), Some(Seq(roadway1, roadway2)), Some(Seq(linearLocation1, linearLocation2)), Some(leftSideProjectLinks ++ rightSideProjectLinks))
      intercept[ProjectValidationException] {
        defaultSectionCalculatorStrategy.assignMValues(leftSideProjectLinks, rightSideProjectLinks, Seq.empty[UserDefinedCalibrationPoint])
      }
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

      val projectLinkLeft1 = ProjectLink(projectLinkId,   RoadPart(9999, 1), Track.apply(2), Discontinuity.Continuous,    0L, 0L, 0L, 0L, None, None, Some("user"), 12345L.toString, 0.0, 30.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomLeft1, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft1), 0L, 0, 0, reversed = false, None, 86400L, roadwayNumber = 12345L)
      val projectLinkLeft2 = ProjectLink(projectLinkId+1, RoadPart(9999, 1), Track.apply(2), Discontinuity.Discontinuous, 0L, 0L, 0L, 0L, None, None, Some("user"), 12346L.toString, 0.0, 30.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomLeft2, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft2), 0L, 0, 0, reversed = false, None, 86400L, roadwayNumber = 12345L)

      val geomRight1 = Seq(Point(5.0, 0.0), Point(5.0, 5.0))
      val geomRight2 = Seq(Point(5.0, 5.0), Point(5.0, 62.0))

      val projectLinkRight1 = ProjectLink(projectLinkId + 2, RoadPart(9999, 1), Track.apply(1), Discontinuity.Continuous,    0L,  5L, 0L,  5L, None, None, Some("user"), 12347L.toString, 0.0,  5.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomRight1, 0L, RoadAddressChangeType.Transfer, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight1), roadwayId, linearLocationId,   0, reversed = false, None, 86400L, roadwayNumber = roadwaynumber)
      val projectLinkRight2 = ProjectLink(projectLinkId + 3, RoadPart(9999, 1), Track.apply(1), Discontinuity.Discontinuous, 5L, 62L, 5L, 62L, None, None, Some("user"), 12348L.toString, 0.0, 57.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomRight2, 0L, RoadAddressChangeType.Transfer, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight2), roadwayId, linearLocationId+1, 0, reversed = false, None, 86400L, roadwayNumber = roadwaynumber)

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

      val projectLinkLeft1 = ProjectLink(projectLinkId,   RoadPart(9999, 1), Track.apply(2), Discontinuity.Continuous,     0L,  0L, 0L,  0L, None, None, Some("user"), 12345L.toString, 0.0, 30.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomLeft1, 0L, RoadAddressChangeType.New, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft1), 0L, 0, 0, reversed = false, None, 86400L, roadwayNumber = 12345L)
      val projectLinkLeft2 = ProjectLink(projectLinkId+1, RoadPart(9999, 1), Track.apply(2), Discontinuity.Discontinuous,  0L,  0L, 0L,  0L, None, None, Some("user"), 12346L.toString, 0.0, 30.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomLeft2, 0L, RoadAddressChangeType.New, AdministrativeClass.State,        LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft2), 0L, 0, 0, reversed = false, None, 86400L, roadwayNumber = 12345L)

      val geomRight1 = Seq(Point(5.0, 0.0), Point(5.0, 5.0))
      val geomRight2 = Seq(Point(5.0, 5.0), Point(5.0, 62.0))

      val projectLinkRight1 = ProjectLink(projectLinkId+2, RoadPart(9999, 1), Track.apply(1), Discontinuity.Continuous,    0L,  5L, 0L,  5L, None, None, Some("user"), 12347L.toString, 0.0,  5.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomRight1, 0L, RoadAddressChangeType.Transfer, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight1), roadwayId, linearLocationId,   0, reversed = false, None, 86400L, roadwayNumber = 12346L)
      val projectLinkRight2 = ProjectLink(projectLinkId+3, RoadPart(9999, 1), Track.apply(1), Discontinuity.Discontinuous, 5L, 62L, 5L, 62L, None, None, Some("user"), 12348L.toString, 0.0, 57.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomRight2, 0L, RoadAddressChangeType.Transfer, AdministrativeClass.State,        LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight2), roadwayId, linearLocationId+1, 0, reversed = false, None, 86400L, roadwayNumber = 12346L)

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

      val projectLinkLeft1 = ProjectLink(nextPlId, RoadPart(9999, 1), Track.apply(2), Discontinuity.Continuous,    0L, 30L, 0L, 30L, None, None, Some("user"), 12345L.toString, 0.0, getEndMValue(geomLeft1), SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomLeft1, 0L, RoadAddressChangeType.Unchanged, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft1), roadwayId,   linearLocationId,   0, reversed = false, None, 86400L, roadwayNumber = 12345L)
      val projectLinkLeft2 = ProjectLink(nextPlId, RoadPart(9999, 1), Track.apply(2), Discontinuity.Continuous,    0L,  0L, 0L,  0L, None, None, Some("user"), 12346L.toString, 0.0, getEndMValue(geomLeft2), SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomLeft2, 0L, RoadAddressChangeType.New,       AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft2), 0L,          0,                  0, reversed = false, None, 86400L)
      val projectLinkLeft3 = ProjectLink(nextPlId, RoadPart(9999, 1), Track.apply(2), Discontinuity.Discontinuous, 0L, 24L, 0L, 24L, None, None, Some("user"), 12347L.toString, 0.0, getEndMValue(geomLeft3), SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomLeft3, 0L, RoadAddressChangeType.Transfer,  AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft3), roadwayId+1, linearLocationId+1, 0, reversed = false, None, 86400L, roadwayNumber = 12347L)

      val geomRight1 = Seq(Point(5.0, 0.0), Point(5.0, 20.0))
      val geomRight2 = Seq(Point(5.0, 20.0), Point(5.0, 62.0))
      val geomRight3 = Seq(Point(5.0, 62.0), Point(5.0, 84.0))

      val projectLinkRight1 = ProjectLink(nextPlId, RoadPart(9999, 1), Track.apply(1), Discontinuity.Continuous,    0L, 20L,  0L, 20L, None, None, Some("user"), 12348L.toString, 0.0, getEndMValue(geomRight1), SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomRight1, 0L, RoadAddressChangeType.Unchanged, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight1), roadwayId+2, linearLocationId+2, 0, reversed = false, None, 86400L, roadwayNumber = 12348L)
      val projectLinkRight2 = ProjectLink(nextPlId, RoadPart(9999, 1), Track.apply(1), Discontinuity.Continuous,   20L, 62L, 20L, 62L, None, None, Some("user"), 12349L.toString, 0.0, getEndMValue(geomRight2), SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomRight2, 0L, RoadAddressChangeType.Unchanged, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight2), roadwayId+3, linearLocationId+3, 0, reversed = false, None, 86400L, roadwayNumber = 12349L)
      val projectLinkRight3 = ProjectLink(nextPlId, RoadPart(9999, 1), Track.apply(1), Discontinuity.Discontinuous, 0L, 22L,  0L, 22L, None, None, Some("user"), 12350L.toString, 0.0, getEndMValue(geomRight3), SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomRight3, 0L, RoadAddressChangeType.Transfer,  AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight3), roadwayId+4, linearLocationId+4, 0, reversed = false, None, 86400L, roadwayNumber = 12350L)

      val leftSideProjectLinks = Seq(projectLinkLeft1, projectLinkLeft2, projectLinkLeft3)
      val rightSideProjectLinks = Seq(projectLinkRight1, projectLinkRight2, projectLinkRight3)

      val (linearLocation1, roadway1) = Seq(projectLinkRight1).map(toRoadwayAndLinearLocation).head
      val (linearLocation2, roadway2) = Seq(projectLinkRight2).map(toRoadwayAndLinearLocation).head
      val (linearLocation3, roadway3) = Seq(projectLinkRight3).map(toRoadwayAndLinearLocation).head
      val (linearLocation4, roadway4) = Seq(projectLinkLeft1).map(toRoadwayAndLinearLocation).head
      val (linearLocation5, roadway5) = Seq(projectLinkLeft3).map(toRoadwayAndLinearLocation).head

      buildTestDataForProject(
        Some(project),
        Some(Seq(roadway1,
          roadway2, roadway3.copy(roadPart = RoadPart(roadway3.roadPart.roadNumber, 2)),
          roadway4, roadway5.copy(roadPart = RoadPart(roadway5.roadPart.roadNumber, 2))
        )),
        Some(Seq(linearLocation1, linearLocation2, linearLocation3, linearLocation4, linearLocation5)),
        Some(leftSideProjectLinks ++ rightSideProjectLinks)
      )

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

      val projectLinkLeft1  = ProjectLink(projectLinkId,   RoadPart(9999, 1), Track.apply(2), Discontinuity.Discontinuous, 0L,  0L, 0L,  0L, None, None, Some("user"), 12345L.toString, 0.0, 60.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomLeft1, 0L, RoadAddressChangeType.New,       AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft1),  0L, 0, 0, reversed = false, None, 86400L, roadwayNumber = 12345L)

      val geomRight1 = Seq(Point(5.0, 0.0), Point(5.0, 5.0))
      val geomRight2 = Seq(Point(5.0, 5.0), Point(5.0, 62.0))

      val projectLinkRight1 = ProjectLink(projectLinkId+2, RoadPart(9999, 1), Track.apply(1), Discontinuity.Continuous,    0L,  5L, 0L,  5L, None, None, Some("user"), 12347L.toString, 0.0,  5.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomRight1, 0L, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight1), roadwayId, linearLocationId,   0, reversed = false, None, 86400L, roadwayNumber = 12346L)
      val projectLinkRight2 = ProjectLink(projectLinkId+3, RoadPart(9999, 1), Track.apply(1), Discontinuity.Discontinuous, 5L, 62L, 5L, 62L, None, None, Some("user"), 12348L.toString, 0.0, 57.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomRight2, 0L, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight2), roadwayId, linearLocationId+1, 0, reversed = false, None, 86400L, roadwayNumber = 12347L)

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

      val projectLinkRight1 = ProjectLink(projectLinkId,     RoadPart(9999, 1), Track.RightSide, Discontinuity.Continuous, 0L, 117L, 0L, 117L, None, None, Some("user"), 12345L.toString, 0.0, 106.169, SideCode.Unknown, (NoCP, NoCP),          (NoCP, NoCP), geomLeft1, projId, RoadAddressChangeType.Unchanged, AdministrativeClass.State,         LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft1), 0L, 0, 0, reversed = false, None, 86400L, roadwayNumber = 12345L)
      val projectLinkRight2 = ProjectLink(projectLinkId + 1, RoadPart(9999, 1), Track.RightSide, Discontinuity.Continuous, 0L,   0L, 0L,   0L, None, None, Some("user"), 12346L.toString, 0.0,  92.849, SideCode.Unknown, (NoCP, NoCP),          (NoCP, NoCP), geomLeft2, projId, RoadAddressChangeType.New,       AdministrativeClass.State,         LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft2), 0L, 0, 0, reversed = false, None, 86400L)

      val geomRight2 = Seq(Point(640647.318, 6945298.805, 81.94899999999325), Point(640640.688, 6945310.671, 81.96700000000419), Point(640613.581, 6945354.331, 82.08299999999872), Point(640583.638, 6945402.111, 82.11299999999756), Point(640555.489, 6945443.729, 82.16999999999825), Point(640535.923, 6945471.794, 82.31699959446674))

      val projectLinkLeft1  = ProjectLink(projectLinkId + 3, RoadPart(9999, 1), Track.LeftSide,  Discontinuity.Continuous, 0L, 228L, 0L, 228L, None, None, Some("user"), 12348L.toString, 0.0, 205.826, SideCode.Unknown, (NoCP, RoadAddressCP), (NoCP, NoCP), geomRight2, projId, RoadAddressChangeType.Unchanged, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight2), roadwayId + 1, linearLocationId + 1, 0, reversed = false, None, 86400L, roadwayNumber = 12347L)

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
      val roadPart       = RoadPart(1, 2)
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
        ProjectLink(1000,roadPart,Track.RightSide,Discontinuity.Continuous,           0,1550,   0,1550,None,None,Some(createdBy),452570.toString, 0.0,  geomRight1length,SideCode.TowardsDigitizing,(RoadAddressCP,  JunctionPointCP),(RoadAddressCP,  JunctionPointCP),geomRight1,                                                        project_id,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality,FrozenLinkInterface,geomRight1length,  761,  4558,1,false,None,1629932416000L,    38259,roadName,None,None,None,None,None),
        ProjectLink(1037,roadPart,Track.RightSide,Discontinuity.Continuous,        1550,1717,1550,1717,None,None,Some(createdBy),451986.toString, 0.0,           170.075,SideCode.TowardsDigitizing,(NoCP,           JunctionPointCP),(NoCP,           JunctionPointCP),List(Point(382891.0,6675103.0,0.0), Point(382737.0,6675175.0,0.0)),project_id,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality,FrozenLinkInterface,         170.075,  761,  4576,1,false,None,1629932416000L,    38259,roadName,None,None,None,None,None),
        ProjectLink(1040,roadPart,Track.RightSide,Discontinuity.MinorDiscontinuity,1717,1742,1717,1742,None,None,Some(createdBy),452008.toString, 0.0,            24.988,SideCode.TowardsDigitizing,(JunctionPointCP,RoadAddressCP  ),(JunctionPointCP,RoadAddressCP  ),List(Point(382737.0,6675175.0,0.0), Point(382714.0,6675186.0,0.0)),project_id,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality,FrozenLinkInterface,          24.988,  761,  4577,1,false,None,1629932416000L,    38259,roadName,None,None,None,None,None),
        ProjectLink(1042,roadPart,Track.RightSide,Discontinuity.Continuous,        1742,1760,1742,1760,None,None,Some(createdBy),452012.toString, 0.0,            19.26 ,SideCode.TowardsDigitizing,(RoadAddressCP,  JunctionPointCP),(RoadAddressCP,  JunctionPointCP),List(Point(382728.0,6675158.0,0.0), Point(382737.0,6675175.0,0.0)),project_id,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality,FrozenLinkInterface,          19.26 ,80546,458872,1,false,None,1629932416000L,202219273,roadName,None,None,None,None,None),
        ProjectLink(1044,roadPart,Track.RightSide,Discontinuity.Continuous,        1760,2547,1760,2547,None,None,Some(createdBy),452007.toString, 0.0,  geomRight2length,SideCode.TowardsDigitizing,(JunctionPointCP,NoCP           ),(JunctionPointCP,NoCP           ),geomRight2,                                                        project_id,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality,FrozenLinkInterface,geomRight2length,80546,458873,1,false,None,1629932416000L,202219273,roadName,None,None,None,None,None),
        ProjectLink(1060,roadPart,Track.RightSide,Discontinuity.Continuous,        2547,2570,2547,2570,None,None,Some(createdBy),450651.toString, 0.0,            23.353,SideCode.TowardsDigitizing,(JunctionPointCP,RoadAddressCP  ),(JunctionPointCP,RoadAddressCP  ),List(Point(382569.0,6675961.0,0.0), Point(382555.0,6675978.0,0.0)),project_id,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality,FrozenLinkInterface,          23.353,80546,458880,1,false,None,1629932416000L,202219273,roadName,None,None,None,None,None),

        ProjectLink(1001,roadPart,Track.LeftSide,Discontinuity.Continuous,            0,1673,   0,1673,None,None,Some(createdBy),452566.toString, 0.0,   geomLeft3length,SideCode.TowardsDigitizing,(RoadAddressCP,  NoCP           ),(RoadAddressCP,  NoCP           ),geomLeft3,                                                         project_id,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality,FrozenLinkInterface, geomLeft3length,  715,  4288,1,false,None,1629932416000L,    38260,roadName,None,None,None,None,None),
        ProjectLink(1038,roadPart,Track.LeftSide,Discontinuity.Continuous,         1673,1726,1673,1726,None,None,Some(createdBy),452005.toString, 0.0,            54.292,SideCode.TowardsDigitizing,(NoCP,           JunctionPointCP),(NoCP,           JunctionPointCP),List(Point(382778.0,6675136.0,0.0), Point(382728.0,6675158.0,0.0)),project_id,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality,FrozenLinkInterface,          54.292,  715,  4307,1,false,None,1629932416000L,    38260,roadName,None,None,None,None,None),
        ProjectLink(1039,roadPart,Track.LeftSide,Discontinuity.Continuous,         1726,1742,1726,1742,None,None,Some(createdBy),452014.toString, 0.0,            16.468,SideCode.TowardsDigitizing,(JunctionPointCP,NoCP           ),(JunctionPointCP,NoCP           ),List(Point(382728.0,6675158.0,0.0), Point(382713.0,6675164.0,0.0)),project_id,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality,FrozenLinkInterface,          16.468,  715,  4308,1,false,None,1629932416000L,    38260,roadName,None,None,None,None,None),
        ProjectLink(1041,roadPart,Track.LeftSide,Discontinuity.Continuous,         1742,1752,1742,1752,None,None,Some(createdBy),452014.toString,16.468,          26.761,SideCode.TowardsDigitizing,(NoCP,           JunctionPointCP),(NoCP,           JunctionPointCP),List(Point(382713.0,6675164.0,0.0), Point(382704.0,6675168.0,0.0)),project_id,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality,FrozenLinkInterface,          10.293,80486,458576,1,false,None,1629932416000L,202219282,roadName,None,None,None,None,None),
        ProjectLink(1043,roadPart,Track.LeftSide,Discontinuity.Continuous,         1752,1773,1752,1773,None,None,Some(createdBy),451992.toString, 0.0,            21.002,SideCode.TowardsDigitizing,(JunctionPointCP,JunctionPointCP),(JunctionPointCP,JunctionPointCP),List(Point(382704.0,6675168.0,0.0), Point(382714.0,6675186.0,0.0)),project_id,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality,FrozenLinkInterface,          21.002,80486,458577,1,false,None,1629932416000L,202219282,roadName,None,None,None,None,None),
        ProjectLink(1045,roadPart,Track.LeftSide,Discontinuity.Continuous,         1773,2438,1773,2438,None,None,Some(createdBy),452002.toString, 0.0,   geomLeft4length,SideCode.TowardsDigitizing,(JunctionPointCP,NoCP           ),(JunctionPointCP,NoCP           ),geomLeft4,                                                         project_id,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality,FrozenLinkInterface, geomLeft4length,80486,458578,1,false,None,1629932416000L,202219282,roadName,None,None,None,None,None),
        ProjectLink(1059,roadPart,Track.LeftSide,Discontinuity.Discontinuous,      2438,2570,2438,2570,None,None,Some(createdBy),450646.toString, 0.0,           134.664,SideCode.TowardsDigitizing,(JunctionPointCP,RoadAddressCP  ),(JunctionPointCP,RoadAddressCP  ),List(Point(382581.0,6675846.0,0.0), Point(382555.0,6675978.0,0.0)),project_id,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality,FrozenLinkInterface,         134.664,80486,458586,1,false,None,1629932416000L,202219282,roadName,None,None,None,None,None)
      )

      val roadways = Seq(
        Roadway(  715,    38260,roadPart,AdministrativeClass.Municipality,Track.LeftSide, Discontinuity.Continuous,        0,1742,reversed = false,DateTime.parse("2018-07-01T00:00:00.000+03:00"),None,createdBy,roadName,1,TerminationCode.NoTermination,DateTime.parse("2018-07-05T00:00:00.000+03:00"),None),
        Roadway(  761,    38259,roadPart,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.MinorDiscontinuity,0,1742,reversed = false,DateTime.parse("2018-07-01T00:00:00.000+03:00"),None,createdBy,roadName,1,TerminationCode.NoTermination,DateTime.parse("2018-07-05T00:00:00.000+03:00"),None),
        Roadway(80486,202219282,roadPart,AdministrativeClass.Municipality,Track.LeftSide, Discontinuity.Discontinuous,  1742,2570,reversed = false,DateTime.parse("2018-07-01T00:00:00.000+03:00"),None,createdBy,roadName,1,TerminationCode.NoTermination,DateTime.parse("2018-07-11T00:00:00.000+03:00"),None),
        Roadway(80546,202219273,roadPart,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Continuous,     1742,2570,reversed = false,DateTime.parse("1989-01-01T00:00:00.000+02:00"),None,createdBy,roadName,1,TerminationCode.NoTermination,DateTime.parse("2018-07-04T00:00:00.000+03:00"),None)
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
      val roadPart = RoadPart(3821, 2)
      val createdBy = "Test"
      val roadName = None
      val projectId = Sequences.nextViiteProjectId
      val project = Project(projectId, ProjectState.Incomplete, "targetAddresses", "s", DateTime.now(), "", DateTime.now(), DateTime.now(), "", Seq(), Seq(), None, None)

      val projectLinks = Seq(
        ProjectLink(1000,roadPart,Track.RightSide,Discontinuity.Continuous,           0, 107,   0, 107,None,None,Some(createdBy), 2621083.toString,  0.0  ,105.305,SideCode.TowardsDigitizing,(RoadAddressCP,  NoCP),(RoadAddressCP,NoCP),List(Point(564071.0,6769520.0,0.0), Point(564172.0,6769549.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,      105.305,45508,756718,3,reversed = false,None,1533863903000L,    79190,roadName,None,None,None,None,None),
        ProjectLink(1001,roadPart,Track.LeftSide, Discontinuity.Continuous,           0, 109,   0, 109,None,None,Some(createdBy), 2621084.toString,  0.0  ,104.894,SideCode.TowardsDigitizing,(RoadAddressCP,  NoCP),(RoadAddressCP,NoCP),List(Point(564063.0,6769533.0,0.0), Point(564164.0,6769562.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,      104.894,73607,743246,3,reversed = false,None,1533863903000L,148127711,roadName,None,None,None,None,None),
        ProjectLink(1002,roadPart,Track.RightSide,Discontinuity.Continuous,         107, 132, 107, 132,None,None,Some(createdBy), 2621089.toString,  0.0  , 24.875,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(564172.0,6769549.0,0.0), Point(564196.0,6769557.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,       24.875,45508,756711,3,reversed = false,None,1533863903000L,    79190,roadName,None,None,None,None,None),
        ProjectLink(1003,roadPart,Track.LeftSide, Discontinuity.Continuous,         109, 135, 109, 135,None,None,Some(createdBy), 2621085.toString,  0.0  , 25.05 ,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(564164.0,6769562.0,0.0), Point(564188.0,6769570.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,       25.05 ,73607,743250,3,reversed = false,None,1533863903000L,148127711,roadName,None,None,None,None,None),
        ProjectLink(1004,roadPart,Track.RightSide,Discontinuity.Continuous,         132, 210, 132, 210,None,None,Some(createdBy), 2621306.toString,  0.0  , 76.535,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(564196.0,6769557.0,0.0), Point(564269.0,6769579.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,       76.535,45508,756714,3,reversed = false,None,1533863903000L,    79190,roadName,None,None,None,None,None),
        ProjectLink(1006,roadPart,Track.LeftSide, Discontinuity.Continuous,         135, 230, 135, 230,None,None,Some(createdBy), 2621305.toString,  0.0  , 91.83 ,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(564188.0,6769570.0,0.0), Point(564275.0,6769597.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,       91.83 ,73607,743248,3,reversed = false,None,1533863903000L,148127711,roadName,None,None,None,None,None),
        ProjectLink(1005,roadPart,Track.RightSide,Discontinuity.Continuous,         210, 221, 210, 221,None,None,Some(createdBy), 2621315.toString,  0.0  , 11.151,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(564269.0,6769579.0,0.0), Point(564280.0,6769583.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,       11.151,45508,756716,3,reversed = false,None,1533863903000L,    79190,roadName,None,None,None,None,None),
        ProjectLink(1007,roadPart,Track.RightSide,Discontinuity.Continuous,         221, 236, 221, 236,None,None,Some(createdBy), 2621318.toString,  0.0  , 14.83 ,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(564280.0,6769583.0,0.0), Point(564294.0,6769587.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,       14.83 ,45508,756710,3,reversed = false,None,1533863903000L,    79190,roadName,None,None,None,None,None),
        ProjectLink(1010,roadPart,Track.LeftSide, Discontinuity.Continuous,         230, 300, 230, 300,None,None,Some(createdBy), 2621314.toString,  0.0  , 66.909,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(564275.0,6769597.0,0.0), Point(564339.0,6769617.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,       66.909,73607,743244,3,reversed = false,None,1533863903000L,148127711,roadName,None,None,None,None,None),
        ProjectLink(1008,roadPart,Track.RightSide,Discontinuity.Continuous,         236, 288, 236, 288,None,None,Some(createdBy), 2621311.toString,  0.0  , 50.696,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(564294.0,6769587.0,0.0), Point(564342.0,6769603.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,       50.696,45508,756720,3,reversed = false,None,1533863903000L,    79190,roadName,None,None,None,None,None),
        ProjectLink(1009,roadPart,Track.RightSide,Discontinuity.Continuous,         288, 296, 288, 296,None,None,Some(createdBy), 2621320.toString,  0.0  ,  8.601,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(564342.0,6769603.0,0.0), Point(564351.0,6769605.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,        8.601,45508,756715,3,reversed = false,None,1533863903000L,    79190,roadName,None,None,None,None,None),
        ProjectLink(1012,roadPart,Track.RightSide,Discontinuity.Continuous,         296, 361, 296, 361,None,None,Some(createdBy), 2621337.toString,  0.0  , 63.921,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(564351.0,6769605.0,0.0), Point(564411.0,6769625.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,       63.921,45508,756712,3,reversed = false,None,1533863903000L,    79190,roadName,None,None,None,None,None),
        ProjectLink(1011,roadPart,Track.LeftSide, Discontinuity.Continuous,         300, 305, 300, 305,None,None,Some(createdBy), 2621309.toString,  0.0  ,  4.571,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(564339.0,6769617.0,0.0), Point(564343.0,6769619.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,        4.571,73607,743249,3,reversed = false,None,1533863903000L,148127711,roadName,None,None,None,None,None),
        ProjectLink(1013,roadPart,Track.LeftSide, Discontinuity.Continuous,         305, 375, 305, 375,None,None,Some(createdBy), 2621326.toString,  0.0  , 67.642,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(564343.0,6769619.0,0.0), Point(564408.0,6769639.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,       67.642,73607,743247,3,reversed = false,None,1533863903000L,148127711,roadName,None,None,None,None,None),
        ProjectLink(1014,roadPart,Track.RightSide,Discontinuity.Continuous,         361, 375, 361, 375,None,None,Some(createdBy), 2621328.toString,  0.0  , 13.674,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(564411.0,6769625.0,0.0), Point(564424.0,6769629.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,       13.674,45508,756717,3,reversed = false,None,1533863903000L,    79190,roadName,None,None,None,None,None),
        ProjectLink(1016,roadPart,Track.RightSide,Discontinuity.Continuous,         375, 465, 375, 465,None,None,Some(createdBy), 2621322.toString,  0.0  , 88.702,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(564424.0,6769629.0,0.0), Point(564508.0,6769659.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,       88.702,45508,756713,3,reversed = false,None,1533863903000L,    79190,roadName,None,None,None,None,None),
        ProjectLink(1015,roadPart,Track.LeftSide, Discontinuity.Continuous,         375, 387, 375, 387,None,None,Some(createdBy), 2621329.toString,  0.0  , 11.818,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(564408.0,6769639.0,0.0), Point(564419.0,6769643.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,       11.818,73607,743245,3,reversed = false,None,1533863903000L,148127711,roadName,None,None,None,None,None),
        ProjectLink(1018,roadPart,Track.LeftSide, Discontinuity.Continuous,         387, 483, 387, 483,None,None,Some(createdBy), 2621323.toString,  0.0  , 92.672,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(564419.0,6769643.0,0.0), Point(564506.0,6769674.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,       92.672,73607,743242,3,reversed = false,None,1533863903000L,148127711,roadName,None,None,None,None,None),
        ProjectLink(1017,roadPart,Track.RightSide,Discontinuity.Continuous,         465, 476, 465, 476,None,None,Some(createdBy), 2621332.toString,  0.0  , 10.767,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(564508.0,6769659.0,0.0), Point(564516.0,6769665.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,       10.767,45508,756719,3,reversed = false,None,1533863903000L,    79190,roadName,None,None,None,None,None),
        ProjectLink(1019,roadPart,Track.RightSide,Discontinuity.Continuous,         476, 507, 476, 507,None,None,Some(createdBy), 2621175.toString,  0.0  , 30.585,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(564516.0,6769665.0,0.0), Point(564534.0,6769690.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,       30.585,45508,756721,3,reversed = false,None,1533863903000L,    79190,roadName,None,None,None,None,None),
        ProjectLink(1020,roadPart,Track.LeftSide, Discontinuity.Continuous,         483, 507, 483, 507,None,None,Some(createdBy), 2621176.toString,  0.0  , 22.565,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(564506.0,6769674.0,0.0), Point(564522.0,6769690.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,       22.565,73607,563344,3,reversed = false,None,1533863903000L,148127711,roadName,None,None,None,None,None),
        ProjectLink(1022,roadPart,Track.RightSide,Discontinuity.MinorDiscontinuity, 507, 561, 507, 561,None,None,Some(createdBy), 2621175.toString, 30.585, 83.861,SideCode.TowardsDigitizing,(NoCP,  RoadAddressCP),(NoCP,RoadAddressCP),List(Point(564534.0,6769690.0,0.0), Point(564547.0,6769741.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,       53.276,45590,351769,3,reversed = false,None,1533863903000L,    79184,roadName,None,None,None,None,None),
        ProjectLink(1021,roadPart,Track.LeftSide, Discontinuity.MinorDiscontinuity, 507, 561, 507, 561,None,None,Some(createdBy), 2621176.toString, 22.565, 75.545,SideCode.TowardsDigitizing,(NoCP,  RoadAddressCP),(NoCP,RoadAddressCP),List(Point(564522.0,6769690.0,0.0), Point(564537.0,6769739.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,       52.98 ,72749,563342,3,reversed = false,None,1533863903000L,148127709,roadName,None,None,None,None,None),
        ProjectLink(1024,roadPart,Track.RightSide,Discontinuity.Continuous,         561, 572, 561, 572,None,None,Some(createdBy), 2621196.toString,  0.0  , 10.965,SideCode.TowardsDigitizing,(RoadAddressCP,  NoCP),(RoadAddressCP,NoCP),List(Point(564547.0,6769759.0,0.0), Point(564547.0,6769770.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,       10.965,80988,754640,3,reversed = false,None,1634598047000L,202230183,roadName,None,None,None,None,None),
        ProjectLink(1023,roadPart,Track.LeftSide, Discontinuity.Continuous,         561, 572, 561, 572,None,None,Some(createdBy), 2621199.toString,  0.0  , 10.459,SideCode.TowardsDigitizing,(RoadAddressCP,  NoCP),(RoadAddressCP,NoCP),List(Point(564537.0,6769760.0,0.0), Point(564539.0,6769770.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,       10.459,81119,462093,3,reversed = false,None,1533863903000L,202230176,roadName,None,None,None,None,None),
        ProjectLink(1026,roadPart,Track.RightSide,Discontinuity.Continuous,         572, 666, 572, 666,None,None,Some(createdBy), 2621223.toString,  0.0  , 90.539,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(564547.0,6769770.0,0.0), Point(564553.0,6769860.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,       90.539,80988,754641,3,reversed = false,None,1634598047000L,202230183,roadName,None,None,None,None,None),
        ProjectLink(1025,roadPart,Track.LeftSide, Discontinuity.Continuous,         572, 622, 572, 622,None,None,Some(createdBy), 2621184.toString,  0.0  , 48.764,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(564539.0,6769770.0,0.0), Point(564542.0,6769819.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,       48.764,81119,462094,3,reversed = false,None,1533863903000L,202230176,roadName,None,None,None,None,None),
        ProjectLink(1028,roadPart,Track.LeftSide, Discontinuity.Continuous,         622, 794, 622, 794,None,None,Some(createdBy), 2621210.toString,  0.0  ,167.07 ,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(564542.0,6769819.0,0.0), Point(564554.0,6769986.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,      167.07 ,81119,462095,3,reversed = false,None,1533863903000L,202230176,roadName,None,None,None,None,None),
        ProjectLink(1027,roadPart,Track.RightSide,Discontinuity.Continuous,         666, 776, 666, 776,None,None,Some(createdBy), 2620906.toString,  0.0  ,106.701,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(564553.0,6769860.0,0.0), Point(564561.0,6769966.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,      106.701,80988,754639,3,reversed = false,None,1533863903000L,202230183,roadName,None,None,None,None,None),
        ProjectLink(1029,roadPart,Track.RightSide,Discontinuity.Continuous,         776, 796, 776, 796,None,None,Some(createdBy), 2620909.toString,  0.0  , 19.337,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(564561.0,6769966.0,0.0), Point(564563.0,6769986.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,       19.337,80988,754642,3,reversed = false,None,1533863903000L,202230183,roadName,None,None,None,None,None),
        ProjectLink(1031,roadPart,Track.LeftSide, Discontinuity.MinorDiscontinuity, 794, 812, 794, 812,None,None,Some(createdBy), 2620912.toString,  0.0  , 17.014,SideCode.TowardsDigitizing,(NoCP,  RoadAddressCP),(NoCP,RoadAddressCP),List(Point(564554.0,6769986.0,0.0), Point(564552.0,6770002.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,       17.014,81119,462096,3,reversed = false,None,1533863903000L,202230176,roadName,None,None,None,None,None),
        ProjectLink(1030,roadPart,Track.RightSide,Discontinuity.MinorDiscontinuity, 796, 812, 796, 812,None,None,Some(createdBy), 2620911.toString,  0.0  , 15.23 ,SideCode.TowardsDigitizing,(NoCP,  RoadAddressCP),(NoCP,RoadAddressCP),List(Point(564563.0,6769986.0,0.0), Point(564564.0,6770001.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,       15.23 ,80988,754638,3,reversed = false,None,1533863903000L,202230183,roadName,None,None,None,None,None),
        ProjectLink(1033,roadPart,Track.RightSide,Discontinuity.Continuous,         812, 831, 812, 831,None,None,Some(createdBy), 2620897.toString,  0.0  , 18.744,SideCode.TowardsDigitizing,(RoadAddressCP,  NoCP),(RoadAddressCP,NoCP),List(Point(564586.0,6770020.0,0.0), Point(564603.0,6770027.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,       18.744,48822,763365,3,reversed = false,None,1634598047000L,  6651364,roadName,None,None,None,None,None),
        ProjectLink(1032,roadPart,Track.LeftSide, Discontinuity.Continuous,         812, 830, 812, 830,None,None,Some(createdBy), 2620896.toString,  0.0  , 17.476,SideCode.TowardsDigitizing,(RoadAddressCP,  NoCP),(RoadAddressCP,NoCP),List(Point(564584.0,6770035.0,0.0), Point(564601.0,6770039.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,       17.476,72754,743231,3,reversed = false,None,1634598047000L,148127686,roadName,None,None,None,None,None),
        ProjectLink(1034,roadPart,Track.LeftSide, Discontinuity.Continuous,         830,1084, 830,1084,None,None,Some(createdBy), 2620884.toString,  0.0  ,249.621,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(564601.0,6770039.0,0.0), Point(564844.0,6770097.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,      249.621,72754,743234,3,reversed = false,None,1634598047000L,148127686,roadName,None,None,None,None,None),
        ProjectLink(1035,roadPart,Track.RightSide,Discontinuity.Continuous,         831,1086, 831,1086,None,None,Some(createdBy), 2620883.toString,  0.0  ,249.906,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(564603.0,6770027.0,0.0), Point(564846.0,6770086.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,      249.906,48822,763361,3,reversed = false,None,1533863903000L,  6651364,roadName,None,None,None,None,None),
        ProjectLink(1036,roadPart,Track.LeftSide, Discontinuity.Continuous,        1084,1242,1084,1242,None,None,Some(createdBy), 2620784.toString,  0.0  ,155.167,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(564844.0,6770097.0,0.0), Point(564995.0,6770133.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,      155.167,72754,743230,3,reversed = false,None,1533863903000L,148127686,roadName,None,None,None,None,None),
        ProjectLink(1037,roadPart,Track.RightSide,Discontinuity.Continuous,        1086,1244,1086,1244,None,None,Some(createdBy), 2620785.toString,  0.0  ,155.679,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(564846.0,6770086.0,0.0), Point(564997.0,6770122.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,      155.679,48822,763362,3,reversed = false,None,1533863903000L,  6651364,roadName,None,None,None,None,None),
        ProjectLink(1038,roadPart,Track.LeftSide, Discontinuity.Continuous,        1242,1248,1242,1248,None,None,Some(createdBy), 2620798.toString,  0.0  ,  5.737,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(564995.0,6770133.0,0.0), Point(565000.0,6770134.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,        5.737,72754,743233,3,reversed = false,None,1533863903000L,148127686,roadName,None,None,None,None,None),
        ProjectLink(1039,roadPart,Track.RightSide,Discontinuity.Continuous,        1244,1251,1244,1251,None,None,Some(createdBy), 2620802.toString,  0.0  ,  6.55 ,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(564997.0,6770122.0,0.0), Point(565004.0,6770123.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,        6.55 ,48822,763363,3,reversed = false,None,1533863903000L,  6651364,roadName,None,None,None,None,None),
        ProjectLink(1040,roadPart,Track.LeftSide, Discontinuity.Continuous,        1248,1590,1248,1590,None,None,Some(createdBy), 2620726.toString,  0.0  ,335.841,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(565000.0,6770134.0,0.0), Point(565327.0,6770212.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,      335.841,72754,743232,3,reversed = false,None,1533863903000L,148127686,roadName,None,None,None,None,None),
        ProjectLink(1041,roadPart,Track.RightSide,Discontinuity.Continuous,        1251,1592,1251,1592,None,None,Some(createdBy), 2620729.toString,  0.0  ,335.083,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(565004.0,6770123.0,0.0), Point(565329.0,6770203.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,      335.083,48822,763364,3,reversed = false,None,1533863903000L,  6651364,roadName,None,None,None,None,None),
        ProjectLink(1043,roadPart,Track.LeftSide, Discontinuity.Continuous,        1590,1656,1590,1656,None,None,Some(createdBy),10648023.toString,  0.0  , 64.061,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(565327.0,6770212.0,0.0), Point(565390.0,6770226.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,       64.061,72754,563330,3,reversed = false,None,1533863903000L,148127686,roadName,None,None,None,None,None),
        ProjectLink(1042,roadPart,Track.RightSide,Discontinuity.Continuous,        1592,1656,1592,1656,None,None,Some(createdBy), 2620635.toString,  0.0  , 63.029,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(565329.0,6770203.0,0.0), Point(565391.0,6770216.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,       63.029,48822,583467,3,reversed = false,None,1533863903000L,  6651364,roadName,None,None,None,None,None),
        ProjectLink(1045,roadPart,Track.RightSide,Discontinuity.Continuous,        1656,1829,1656,1829,None,None,Some(createdBy), 2620635.toString, 63.029,233.406,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(565391.0,6770216.0,0.0), Point(565559.0,6770239.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,      170.377,72976,562493,3,reversed = false,None,1533863903000L,148125309,roadName,None,None,None,None,None),
        ProjectLink(1044,roadPart,Track.LeftSide, Discontinuity.Continuous,        1656,1829,1656,1829,None,None,Some(createdBy),10648023.toString, 64.061,233.576,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(565390.0,6770226.0,0.0), Point(565557.0,6770251.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,      169.515,74788,564163,3,reversed = false,None,1533863903000L,148128101,roadName,None,None,None,None,None),
        ProjectLink(1046,roadPart,Track.RightSide,Discontinuity.Continuous,        1829,1854,1829,1854,None,None,Some(createdBy), 2620697.toString,  0.0  , 24.014,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(565559.0,6770239.0,0.0), Point(565583.0,6770242.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,       24.014,72976,742393,3,reversed = false,None,1533863903000L,148125309,roadName,None,None,None,None,None),
        ProjectLink(1047,roadPart,Track.LeftSide, Discontinuity.Continuous,        1829,1857,1829,1857,None,None,Some(createdBy), 2620700.toString,  0.0  , 27.864,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(565557.0,6770251.0,0.0), Point(565585.0,6770255.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,       27.864,74788,744060,3,reversed = false,None,1533863903000L,148128101,roadName,None,None,None,None,None),
        ProjectLink(1049,roadPart,Track.RightSide,Discontinuity.Continuous,        1854,2098,1854,2098,None,None,Some(createdBy), 2620680.toString,  0.0  ,239.642,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(565583.0,6770242.0,0.0), Point(565820.0,6770277.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,      239.642,72976,742394,3,reversed = false,None,1634598047000L,148125309,roadName,None,None,None,None,None),
        ProjectLink(1048,roadPart,Track.LeftSide, Discontinuity.Continuous,        1857,2098,1857,2098,None,None,Some(createdBy), 2620681.toString,  0.0  ,236.094,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(565585.0,6770255.0,0.0), Point(565819.0,6770286.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,      236.094,74788,744061,3,reversed = false,None,1634598047000L,148128101,roadName,None,None,None,None,None),
        ProjectLink(1050,roadPart,Track.RightSide,Discontinuity.Continuous,        2098,2190,2098,2190,None,None,Some(createdBy), 2618679.toString,  0.0  , 91.243,SideCode.TowardsDigitizing,(NoCP,  RoadAddressCP),(NoCP,RoadAddressCP),List(Point(565820.0,6770277.0,0.0), Point(565908.0,6770303.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,       91.243,72976,742395,3,reversed = false,None,1634598047000L,148125309,roadName,None,None,None,None,None),
        ProjectLink(1051,roadPart,Track.LeftSide, Discontinuity.Continuous,        2098,2190,2098,2190,None,None,Some(createdBy), 2618678.toString,  0.0  , 90.718,SideCode.TowardsDigitizing,(NoCP,  RoadAddressCP),(NoCP,RoadAddressCP),List(Point(565819.0,6770286.0,0.0), Point(565908.0,6770303.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,       90.718,74788,744063,3,reversed = false,None,1634598047000L,148128101,roadName,None,None,None,None,None),
        ProjectLink(1052,roadPart,Track.Combined, Discontinuity.Continuous,        2190,2501,2190,2501,None,None,Some(createdBy), 2618671.toString,  0.0  ,309.535,SideCode.TowardsDigitizing,(RoadAddressCP,  NoCP),(RoadAddressCP,NoCP),List(Point(565908.0,6770303.0,0.0), Point(566205.0,6770388.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,      309.535,72949,562439,3,reversed = false,None,1630018852000L,148125051,roadName,None,None,None,None,None),
        ProjectLink(1053,roadPart,Track.Combined, Discontinuity.Continuous,        2501,2637,2501,2637,None,None,Some(createdBy), 2618703.toString,  0.0  ,135.096,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(566205.0,6770388.0,0.0), Point(566332.0,6770434.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,      135.096,72949,562441,3,reversed = false,None,1630018852000L,148125051,roadName,None,None,None,None,None),
        ProjectLink(1054,roadPart,Track.Combined, Discontinuity.Continuous,        2637,2656,2637,2656,None,None,Some(createdBy), 2618716.toString,  0.0  , 19.208,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(566332.0,6770434.0,0.0), Point(566351.0,6770439.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,       19.208,72949,562438,3,reversed = false,None,1630018852000L,148125051,roadName,None,None,None,None,None),
        ProjectLink(1055,roadPart,Track.Combined, Discontinuity.Continuous,        2656,2790,2656,2790,None,None,Some(createdBy), 2618480.toString,  0.0  ,133.577,SideCode.AgainstDigitizing,(NoCP,  RoadAddressCP),(NoCP,RoadAddressCP),List(Point(566482.0,6770432.0,0.0), Point(566351.0,6770439.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,      133.577,72949,562440,3,reversed = false,None,1630018852000L,148125051,roadName,None,None,None,None,None),
        ProjectLink(1056,roadPart,Track.RightSide,Discontinuity.MinorDiscontinuity,2790,2881,2790,2881,None,None,Some(createdBy), 2618583.toString,  0.0  , 91.162,SideCode.AgainstDigitizing,(RoadAddressCP,  NoCP),(RoadAddressCP,NoCP),List(Point(566561.0,6770386.0,0.0), Point(566482.0,6770432.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,       91.162,73430,574704,3,reversed = false,None,1630018852000L,148124318,roadName,None,None,None,None,None),
        ProjectLink(1057,roadPart,Track.LeftSide,Discontinuity.MinorDiscontinuity, 2790,2883,2790,2883,None,None,Some(createdBy), 2618584.toString,  0.0  , 93.474,SideCode.AgainstDigitizing,(RoadAddressCP,  NoCP),(RoadAddressCP,NoCP),List(Point(566567.0,6770395.0,0.0), Point(566482.0,6770432.0,0.0)),projectId,RoadAddressChangeType.Unchanged,  AdministrativeClass.Municipality,NormalLinkInterface,       93.474,74754,564156,3,reversed = false,None,1630018852000L,148128092,roadName,None,None,None,None,None),
        ProjectLink(1059,roadPart,Track.RightSide,Discontinuity.Continuous,        2881,2917,2881,2917,None,None,Some(createdBy),12675142.toString,  0.0  , 35.227,SideCode.AgainstDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(566591.0,6770368.0,0.0), Point(566561.0,6770386.0,0.0)),projectId,RoadAddressChangeType.Termination,AdministrativeClass.Municipality,ComplementaryLinkInterface,35.227,73430,574702,3,reversed = false,None,1652734800000L,148124318,roadName,None,None,None,None,None),
        ProjectLink(1058,roadPart,Track.LeftSide, Discontinuity.Continuous,        2883,2917,2883,2917,None,None,Some(createdBy),12675141.toString,  0.0  , 34.254,SideCode.AgainstDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(566595.0,6770376.0,0.0), Point(566567.0,6770395.0,0.0)),projectId,RoadAddressChangeType.Termination,AdministrativeClass.Municipality,ComplementaryLinkInterface,34.254,74754,564160,3,reversed = false,None,1652734800000L,148128092,roadName,None,None,None,None,None),
        ProjectLink(1061,roadPart,Track.RightSide,Discontinuity.Continuous,        2917,2946,2917,2946,None,None,Some(createdBy), 2618600.toString,  0.0  , 28.861,SideCode.AgainstDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(566617.0,6770354.0,0.0), Point(566591.0,6770368.0,0.0)),projectId,RoadAddressChangeType.Transfer,   AdministrativeClass.Municipality,NormalLinkInterface,       28.861,73430,574705,3,reversed = false,None,1630018852000L,148124318,roadName,None,None,None,None,None),
        ProjectLink(1060,roadPart,Track.LeftSide, Discontinuity.Continuous,        2917,2945,2917,2945,None,None,Some(createdBy), 2618589.toString,  0.0  , 28.254,SideCode.AgainstDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(566620.0,6770362.0,0.0), Point(566595.0,6770376.0,0.0)),projectId,RoadAddressChangeType.Transfer,   AdministrativeClass.Municipality,NormalLinkInterface,       28.254,74754,564157,3,reversed = false,None,1630018852000L,148128092,roadName,None,None,None,None,None),
        ProjectLink(1062,roadPart,Track.LeftSide, Discontinuity.Continuous,        2945,2957,2945,2957,None,None,Some(createdBy), 2618605.toString,  0.0  , 12.173,SideCode.AgainstDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(566631.0,6770356.0,0.0), Point(566620.0,6770362.0,0.0)),projectId,RoadAddressChangeType.Transfer,   AdministrativeClass.Municipality,NormalLinkInterface,       12.173,74754,564159,3,reversed = false,None,1630018852000L,148128092,roadName,None,None,None,None,None),
        ProjectLink(1063,roadPart,Track.RightSide,Discontinuity.Continuous,        2946,2961,2946,2961,None,None,Some(createdBy), 2618604.toString,  0.0  , 14.931,SideCode.AgainstDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(566630.0,6770347.0,0.0), Point(566617.0,6770354.0,0.0)),projectId,RoadAddressChangeType.Transfer,   AdministrativeClass.Municipality,NormalLinkInterface,       14.931,73430,574701,3,reversed = false,None,1630018852000L,148124318,roadName,None,None,None,None,None),
        ProjectLink(1064,roadPart,Track.LeftSide, Discontinuity.Continuous,        2957,3020,2957,3020,None,None,Some(createdBy), 2618597.toString,  0.0  , 63.045,SideCode.AgainstDigitizing,(NoCP,           NoCP),(NoCP,RoadAddressCP),List(Point(566683.0,6770321.0,0.0), Point(566631.0,6770356.0,0.0)),projectId,RoadAddressChangeType.Transfer,   AdministrativeClass.Municipality,NormalLinkInterface,       63.045,74754,564158,3,reversed = false,None,1630018852000L,148128092,roadName,None,None,None,None,None),
        ProjectLink(1065,roadPart,Track.RightSide,Discontinuity.Continuous,        2961,3020,2961,3020,None,None,Some(createdBy), 2618596.toString,  0.0  , 59.188,SideCode.AgainstDigitizing,(NoCP,           NoCP),(NoCP,RoadAddressCP),List(Point(566683.0,6770321.0,0.0), Point(566630.0,6770347.0,0.0)),projectId,RoadAddressChangeType.Transfer,   AdministrativeClass.Municipality,NormalLinkInterface,       59.188,73430,574703,3,reversed = false,None,1630018852000L,148124318,roadName,None,None,None,None,None),
        ProjectLink(1066,roadPart,Track.Combined, Discontinuity.Continuous,        3020,3046,3020,3046,None,None,Some(createdBy), 2618610.toString,  0.0  , 25.827,SideCode.AgainstDigitizing,(NoCP,           NoCP),(RoadAddressCP,NoCP),List(Point(566705.0,6770308.0,0.0), Point(566683.0,6770321.0,0.0)),projectId,RoadAddressChangeType.Transfer,   AdministrativeClass.Municipality,NormalLinkInterface,       25.827,73303,754554,3,reversed = false,None,1630018852000L,148122120,roadName,None,None,None,None,None),
        ProjectLink(1067,roadPart,Track.Combined, Discontinuity.Continuous,        3046,3376,3046,3376,None,None,Some(createdBy), 2618571.toString,  0.0  ,327.094,SideCode.AgainstDigitizing,(NoCP,           NoCP),(NoCP,         NoCP),List(Point(566995.0,6770160.0,0.0), Point(566705.0,6770308.0,0.0)),projectId,RoadAddressChangeType.Transfer,   AdministrativeClass.Municipality,NormalLinkInterface,      327.094,73303,754555,3,reversed = false,None,1630018852000L,148122120,roadName,None,None,None,None,None),
        ProjectLink(1068,roadPart,Track.Combined, Discontinuity.Continuous,        3376,4136,3376,4136,None,None,Some(createdBy), 2618435.toString,  0.0  ,754.015,SideCode.TowardsDigitizing,(NoCP,JunctionPointCP),(NoCP,           JunctionPointCP),List(Point(566995.0,6770160.0,0.0), Point(567657.0,6770430.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,754.015,73303,754556,3,reversed = false,None,1630018852000L,148122120,roadName,None,None,None,None,None),
        ProjectLink(1069,roadPart,Track.Combined, Discontinuity.Continuous,        4136,4243,4136,4243,None,None,Some(createdBy), 2618392.toString,  0.0  ,105.993,SideCode.TowardsDigitizing,(JunctionPointCP,NoCP),(JunctionPointCP,NoCP           ),List(Point(567657.0,6770430.0,0.0), Point(567706.0,6770524.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,105.993,73303,754557,3,reversed = false,None,1630018852000L,148122120,roadName,None,None,None,None,None),
        ProjectLink(1070,roadPart,Track.Combined, Discontinuity.Continuous,        4243,4262,4243,4262,None,None,Some(createdBy), 2618315.toString,  0.0  , 18.446,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,           NoCP           ),List(Point(567706.0,6770524.0,0.0), Point(567713.0,6770541.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.Municipality,NormalLinkInterface, 18.446,73303,754559,3,reversed = false,None,1630018852000L,148122120,roadName,None,None,None,None,None),
        ProjectLink(1071,roadPart,Track.Combined, Discontinuity.Continuous,        4262,4375,4262,4375,None,None,Some(createdBy), 2618374.toString,  0.0  ,112.356,SideCode.TowardsDigitizing,(NoCP,JunctionPointCP),(NoCP,           JunctionPointCP),List(Point(567713.0,6770541.0,0.0), Point(567758.0,6770644.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,112.356,73303,754558,3,reversed = false,None,1630018852000L,148122120,roadName,None,None,None,None,None),
        ProjectLink(1072,roadPart,Track.Combined, Discontinuity.Continuous,        4375,4399,4375,4399,None,None,Some(createdBy), 2618378.toString,  0.0  , 23.905,SideCode.TowardsDigitizing,(JunctionPointCP,NoCP),(JunctionPointCP,NoCP           ),List(Point(567758.0,6770644.0,0.0), Point(567771.0,6770664.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.Municipality,NormalLinkInterface, 23.905,73303,754553,3,reversed = false,None,1630018852000L,148122120,roadName,None,None,None,None,None),
        ProjectLink(1073,roadPart,Track.Combined, Discontinuity.Continuous,        4399,4457,4399,4457,None,None,Some(createdBy), 2618361.toString,  0.0  , 56.683,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,           RoadAddressCP  ),List(Point(567771.0,6770664.0,0.0), Point(567803.0,6770711.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.Municipality,NormalLinkInterface, 56.683,73303,754560,3,reversed = false,None,1630018852000L,148122120,roadName,None,None,None,None,None),
        ProjectLink(1075,roadPart,Track.RightSide,Discontinuity.Continuous,        4457,4561,4457,4561,None,None,Some(createdBy), 2618357.toString,  0.0  , 99.048,SideCode.TowardsDigitizing,(NoCP,           NoCP),(RoadAddressCP,  NoCP           ),List(Point(567803.0,6770711.0,0.0), Point(567864.0,6770789.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.Municipality,NormalLinkInterface, 99.048,14278,708099,3,reversed = false,None,1630018852000L,    50308,roadName,None,None,None,None,None),
        ProjectLink(1074,roadPart,Track.LeftSide, Discontinuity.Continuous,        4457,4558,4457,4558,None,None,Some(createdBy), 2618358.toString,  0.0  , 98.608,SideCode.TowardsDigitizing,(NoCP,           NoCP),(RoadAddressCP,  NoCP           ),List(Point(567803.0,6770711.0,0.0), Point(567855.0,6770794.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.Municipality,NormalLinkInterface, 98.608,73190,743191,3,reversed = false,None,1630018852000L,148127472,roadName,None,None,None,None,None),
        ProjectLink(1076,roadPart,Track.LeftSide, Discontinuity.Continuous,        4558,4934,4558,4934,None,None,Some(createdBy), 2618335.toString,  0.0  ,367.046,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,           NoCP           ),List(Point(567855.0,6770794.0,0.0), Point(568063.0,6771097.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,367.046,73190,743193,3,reversed = false,None,1630018852000L,148127472,roadName,None,None,None,None,None),
        ProjectLink(1078,roadPart,Track.RightSide,Discontinuity.Continuous,        4561,5012,4561,5012,None,None,Some(createdBy), 2618337.toString,  0.0  ,428.813,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,           NoCP           ),List(Point(567864.0,6770789.0,0.0), Point(568107.0,6771142.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,428.813,14278,708098,3,reversed = false,None,1630018852000L,    50308,roadName,None,None,None,None,None),
        ProjectLink(1077,roadPart,Track.LeftSide, Discontinuity.Continuous,        4934,4997,4934,4997,None,None,Some(createdBy), 2618344.toString,  0.0  , 61.982,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,           NoCP           ),List(Point(568063.0,6771097.0,0.0), Point(568098.0,6771148.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.Municipality,NormalLinkInterface, 61.982,73190,743194,3,reversed = false,None,1630018852000L,148127472,roadName,None,None,None,None,None),
        ProjectLink(1079,roadPart,Track.LeftSide, Discontinuity.Continuous,        4997,5179,4997,5179,None,None,Some(createdBy), 6929618.toString,  0.0  ,177.61 ,SideCode.TowardsDigitizing,(NoCP,JunctionPointCP),(NoCP,           JunctionPointCP),List(Point(568098.0,6771148.0,0.0), Point(568207.0,6771287.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,177.61 ,73190,563296,3,reversed = false,None,1630018852000L,148127472,roadName,None,None,None,None,None),
        ProjectLink(1081,roadPart,Track.RightSide,Discontinuity.Continuous,        5012,5188,5012,5188,None,None,Some(createdBy), 2618153.toString,  0.0  ,166.64 ,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,           NoCP           ),List(Point(568107.0,6771142.0,0.0), Point(568209.0,6771272.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,166.64 ,14278,528201,3,reversed = false,None,1630018852000L,    50308,roadName,None,None,None,None,None),
        ProjectLink(1080,roadPart,Track.LeftSide, Discontinuity.MinorDiscontinuity,5179,5188,5179,5188,None,None,Some(createdBy), 6929633.toString,  0.0  ,  8.487,SideCode.TowardsDigitizing,(JunctionPointCP,NoCP),(JunctionPointCP,RoadAddressCP  ),List(Point(568207.0,6771287.0,0.0), Point(568214.0,6771292.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,  8.487,73190,563293,3,reversed = false,None,1630018852000L,148127472,roadName,None,None,None,None,None),
        ProjectLink(1083,roadPart,Track.RightSide,Discontinuity.Continuous,        5188,5199,5188,5199,None,None,Some(createdBy), 2618153.toString,166.64 ,177.175,SideCode.TowardsDigitizing,(NoCP,JunctionPointCP),(NoCP,           JunctionPointCP),List(Point(568209.0,6771272.0,0.0), Point(568217.0,6771280.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.Municipality,NormalLinkInterface, 10.535,81129,574010,3,reversed = false,None,1630018852000L,202228103,roadName,None,None,None,None,None),
        ProjectLink(1082,roadPart,Track.LeftSide, Discontinuity.Continuous,        5188,5199,5188,5199,None,None,Some(createdBy), 6929632.toString,  0.0  , 11.754,SideCode.TowardsDigitizing,(NoCP,JunctionPointCP),(RoadAddressCP,  JunctionPointCP),List(Point(568217.0,6771280.0,0.0), Point(568207.0,6771287.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.Municipality,NormalLinkInterface, 11.754,80697,574748,3,reversed = false,None,1630018852000L,202230193,roadName,None,None,None,None,None),
        ProjectLink(1085,roadPart,Track.RightSide,Discontinuity.Continuous,        5199,5213,5199,5213,None,None,Some(createdBy), 2618186.toString,  0.0  , 12.661,SideCode.TowardsDigitizing,(JunctionPointCP,JunctionPointCP),(JunctionPointCP,JunctionPointCP),List(Point(568217.0,6771280.0,0.0), Point(568214.0,6771292.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,12.661,81129,574017,3,reversed = false,None,1630018852000L,202228103,roadName,None,None,None,None,None),
        ProjectLink(1084,roadPart,Track.LeftSide, Discontinuity.Continuous,        5199,5209,5199,5209,None,None,Some(createdBy), 6929630.toString,  0.0  ,  9.466,SideCode.TowardsDigitizing,(JunctionPointCP,NoCP),(JunctionPointCP,NoCP           ),List(Point(568207.0,6771287.0,0.0), Point(568202.0,6771295.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,  9.466,80697,574747,3,reversed = false,None,1630018852000L,202230193,roadName,None,None,None,None,None),
        ProjectLink(1087,roadPart,Track.LeftSide, Discontinuity.Continuous,        5209,5267,5209,5267,None,None,Some(createdBy), 2618159.toString,  0.0  , 57.38 ,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,           NoCP           ),List(Point(568202.0,6771295.0,0.0), Point(568191.0,6771349.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.Municipality,NormalLinkInterface, 57.38 ,80697,574750,3,reversed = false,None,1630018852000L,202230193,roadName,None,None,None,None,None),
        ProjectLink(1086,roadPart,Track.RightSide,Discontinuity.Continuous,        5213,5223,5213,5223,None,None,Some(createdBy), 6929628.toString,  0.0  ,  9.37 ,SideCode.TowardsDigitizing,(JunctionPointCP,NoCP),(JunctionPointCP,NoCP           ),List(Point(568214.0,6771292.0,0.0), Point(568209.0,6771300.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,  9.37 ,81129,753914,3,reversed = false,None,1630018852000L,202228103,roadName,None,None,None,None,None),
        ProjectLink(1088,roadPart,Track.RightSide,Discontinuity.Continuous,        5223,5275,5223,5275,None,None,Some(createdBy), 6929627.toString,  0.0  , 49.032,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,           NoCP           ),List(Point(568209.0,6771300.0,0.0), Point(568200.0,6771347.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.Municipality,NormalLinkInterface, 49.032,81129,753912,3,reversed = false,None,1630018852000L,202228103,roadName,None,None,None,None,None),
        ProjectLink(1090,roadPart,Track.LeftSide, Discontinuity.Continuous,        5267,5331,5267,5331,None,None,Some(createdBy), 2618179.toString,  0.0  , 62.471,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,           NoCP           ),List(Point(568191.0,6771349.0,0.0), Point(568240.0,6771383.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.Municipality,NormalLinkInterface, 62.471,80697,574744,3,reversed = false,None,1630018852000L,202230193,roadName,None,None,None,None,None),
        ProjectLink(1089,roadPart,Track.RightSide,Discontinuity.Continuous,        5275,5326,5275,5326,None,None,Some(createdBy), 2618178.toString,  0.0  , 48.914,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,           NoCP           ),List(Point(568200.0,6771347.0,0.0), Point(568239.0,6771372.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.Municipality,NormalLinkInterface, 48.914,81129,753908,3,reversed = false,None,1630018852000L,202228103,roadName,None,None,None,None,None),
        ProjectLink(1091,roadPart,Track.RightSide,Discontinuity.Continuous,        5326,5377,5326,5377,None,None,Some(createdBy), 2618182.toString,  0.0  , 48.654,SideCode.AgainstDigitizing,(NoCP,           NoCP),(NoCP,           NoCP           ),List(Point(568284.0,6771355.0,0.0), Point(568239.0,6771372.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.Municipality,NormalLinkInterface, 48.654,81129,574012,3,reversed = false,None,1630018852000L,202228103,roadName,None,None,None,None,None),
        ProjectLink(1092,roadPart,Track.LeftSide, Discontinuity.Continuous,        5331,5388,5331,5388,None,None,Some(createdBy), 2618183.toString,  0.0  , 55.645,SideCode.AgainstDigitizing,(NoCP,           NoCP),(NoCP,           NoCP           ),List(Point(568290.0,6771361.0,0.0), Point(568240.0,6771383.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.Municipality,NormalLinkInterface, 55.645,80697,754650,3,reversed = false,None,1630018852000L,202230193,roadName,None,None,None,None,None),
        ProjectLink(1093,roadPart,Track.RightSide,Discontinuity.Continuous,        5377,5419,5377,5419,None,None,Some(createdBy), 2618173.toString,  0.0  , 39.387,SideCode.AgainstDigitizing,(NoCP,           NoCP),(NoCP,           NoCP           ),List(Point(568316.0,6771332.0,0.0), Point(568284.0,6771355.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.Municipality,NormalLinkInterface, 39.387,81129,753913,3,reversed = false,None,1630018852000L,202228103,roadName,None,None,None,None,None),
        ProjectLink(1094,roadPart,Track.LeftSide, Discontinuity.Continuous,        5388,5427,5388,5427,None,None,Some(createdBy), 2618174.toString,  0.0  , 38.244,SideCode.AgainstDigitizing,(NoCP,           NoCP),(NoCP,           NoCP           ),List(Point(568320.0,6771337.0,0.0), Point(568290.0,6771361.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.Municipality,NormalLinkInterface, 38.244,80697,754644,3,reversed = false,None,1630018852000L,202230193,roadName,None,None,None,None,None),
        ProjectLink(1096,roadPart,Track.RightSide,Discontinuity.Continuous,        5419,5638,5419,5638,None,None,Some(createdBy), 2618974.toString,  0.0  ,207.456,SideCode.AgainstDigitizing,(NoCP,           NoCP),(NoCP,           NoCP           ),List(Point(568498.0,6771234.0,0.0), Point(568316.0,6771332.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,207.456,81129,574011,3,reversed = false,None,1630018852000L,202228103,roadName,None,None,None,None,None),
        ProjectLink(1095,roadPart,Track.LeftSide, Discontinuity.Continuous,        5427,5637,5427,5637,None,None,Some(createdBy), 2618973.toString,  0.0  ,205.311,SideCode.AgainstDigitizing,(NoCP,           NoCP),(NoCP,           NoCP           ),List(Point(568507.0,6771257.0,0.0), Point(568320.0,6771337.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.Municipality,NormalLinkInterface,205.311,80697,574749,3,reversed = false,None,1630018852000L,202230193,roadName,None,None,None,None,None),
        ProjectLink(1097,roadPart,Track.LeftSide, Discontinuity.MinorDiscontinuity,5637,5651,5637,5651,None,None,Some(createdBy), 2618942.toString,  0.0  , 13.458,SideCode.AgainstDigitizing,(NoCP,           NoCP),(NoCP,           RoadAddressCP  ),List(Point(568520.0,6771254.0,0.0), Point(568507.0,6771257.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.Municipality,NormalLinkInterface, 13.458,80697,574746,3,reversed = false,None,1630018852000L,202230193,roadName,None,None,None,None,None),
        ProjectLink(1098,roadPart,Track.RightSide,Discontinuity.MinorDiscontinuity,5638,5651,5638,5651,None,None,Some(createdBy), 2618955.toString,  0.0  , 12.919,SideCode.AgainstDigitizing,(NoCP,           NoCP),(NoCP,           RoadAddressCP  ),List(Point(568509.0,6771228.0,0.0), Point(568498.0,6771234.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.Municipality,NormalLinkInterface, 12.919,81129,574016,3,reversed = false,None,1630018852000L,202228103,roadName,None,None,None,None,None),
        ProjectLink(1099,roadPart,Track.RightSide,Discontinuity.Continuous,        5651,5674,5651,5674,None,None,Some(createdBy), 2618948.toString,  0.0  , 21.242,SideCode.TowardsDigitizing,(NoCP,           NoCP),(RoadAddressCP,  NoCP           ),List(Point(568565.0,6771209.0,0.0), Point(568587.0,6771210.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.Municipality,NormalLinkInterface, 21.242,81365,574755,3,reversed = false,None,1630018852000L,202230207,roadName,None,None,None,None,None),
        ProjectLink(1100,roadPart,Track.LeftSide, Discontinuity.Continuous,        5651,5675,5651,5675,None,None,Some(createdBy), 2618945.toString,  0.0  , 23.343,SideCode.AgainstDigitizing,(NoCP,           NoCP),(RoadAddressCP,  NoCP           ),List(Point(568589.0,6771224.0,0.0), Point(568572.0,6771239.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.Municipality,NormalLinkInterface, 23.343,80536,574752,3,reversed = false,None,1630018852000L,202230200,roadName,None,None,None,None,None),
        ProjectLink(1102,roadPart,Track.RightSide,Discontinuity.Continuous,        5674,5708,5674,5708,None,None,Some(createdBy), 2618964.toString,  0.0  , 30.459,SideCode.TowardsDigitizing,(NoCP,           NoCP),(NoCP,           RoadAddressCP  ),List(Point(568587.0,6771210.0,0.0), Point(568617.0,6771210.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.Municipality,NormalLinkInterface, 30.459,81365,574754,3,reversed = false,None,1630018852000L,202230207,roadName,None,None,None,None,None),
        ProjectLink(1101,roadPart,Track.LeftSide, Discontinuity.Continuous,        5675,5708,5675,5708,None,None,Some(createdBy), 2618963.toString,  0.0  , 31.354,SideCode.AgainstDigitizing,(NoCP,           NoCP),(NoCP,           RoadAddressCP  ),List(Point(568617.0,6771210.0,0.0), Point(568589.0,6771224.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.Municipality,NormalLinkInterface, 31.354,80536,574753,3,reversed = false,None,1630018852000L,202230200,roadName,None,None,None,None,None),
        ProjectLink(1103,roadPart,Track.Combined, Discontinuity.Continuous,        5708,5910,5708,5910,None,None,Some(createdBy), 2618918.toString,  0.0  ,201.305,SideCode.AgainstDigitizing,(NoCP,           NoCP),(RoadAddressCP,  NoCP           ),List(Point(568816.0,6771179.0,0.0), Point(568617.0,6771210.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.State,       NormalLinkInterface,201.305,13006,526153,3,reversed = false,None,1630018852000L,    49024,roadName,None,None,None,None,None),
        ProjectLink(1104,roadPart,Track.Combined, Discontinuity.Continuous,        5910,6322,5910,6322,None,None,Some(createdBy),11140159.toString,  0.0  ,409.401,SideCode.AgainstDigitizing,(NoCP,           NoCP),(NoCP,           NoCP           ),List(Point(569219.0,6771105.0,0.0), Point(568816.0,6771179.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.State,       NormalLinkInterface,409.401,13006,706053,3,reversed = false,None,1630018852000L,    49024,roadName,None,None,None,None,None),
        ProjectLink(1105,roadPart,Track.Combined, Discontinuity.Continuous,        6322,6331,6322,6331,None,None,Some(createdBy),11142353.toString,  0.0  ,  8.883,SideCode.AgainstDigitizing,(NoCP,           NoCP),(NoCP,           NoCP           ),List(Point(569227.0,6771103.0,0.0), Point(569219.0,6771105.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.State,       NormalLinkInterface,  8.883,13006,706055,3,reversed = false,None,1635808409000L,    49024,roadName,None,None,None,None,None),
        ProjectLink(1106,roadPart,Track.Combined, Discontinuity.Continuous,        6331,6404,6331,6404,None,None,Some(createdBy),11275750.toString,  0.0  , 72.467,SideCode.AgainstDigitizing,(NoCP,JunctionPointCP),(NoCP,           JunctionPointCP),List(Point(569298.0,6771087.0,0.0), Point(569227.0,6771103.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.State,       NormalLinkInterface, 72.467,13006,706054,3,reversed = false,None,1630018852000L,    49024,roadName,None,None,None,None,None),
        ProjectLink(1107,roadPart,Track.Combined, Discontinuity.EndOfRoad,         6404,6826,6404,6826,None,None,Some(createdBy), 2618768.toString,  0.0  ,417.78 ,SideCode.AgainstDigitizing,(JunctionPointCP,NoCP),(JunctionPointCP,RoadAddressCP  ),List(Point(569685.0,6770938.0,0.0), Point(569298.0,6771087.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.State,       NormalLinkInterface,417.78 ,13006,526157,3,reversed = false,None,1630018852000L,    49024,roadName,None,None,None,None,None)
      )

      val roadways = Seq(
        Roadway(13006,    49024,RoadPart(3821,2),AdministrativeClass.State,       Track.Combined, Discontinuity.EndOfRoad,          5708,6826,reversed = false,DateTime.parse("2005-06-30T00:00:00.000+03:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2009-12-09T00:00:00.000+02:00"),None),
        Roadway(14278,    50308,RoadPart(3821,2),AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Continuous,         4457,5188,reversed = false,DateTime.parse("2018-07-01T00:00:00.000+03:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2018-07-05T00:00:00.000+03:00"),None),
        Roadway(45590,    79184,RoadPart(3821,2),AdministrativeClass.Municipality,Track.RightSide,Discontinuity.MinorDiscontinuity,  507, 561,reversed = false,DateTime.parse("2018-07-01T00:00:00.000+03:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2018-07-05T00:00:00.000+03:00"),None),
        Roadway(45508,    79190,RoadPart(3821,2),AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Continuous,            0, 507,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-30T00:00:00.000+03:00"),None),
        Roadway(48822,  6651364,RoadPart(3821,2),AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Continuous,          812,1656,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-30T00:00:00.000+03:00"),None),
        Roadway(59859, 43166556,RoadPart(3821,1),AdministrativeClass.Municipality,Track.Combined, Discontinuity.Continuous,          780, 909,reversed = false,DateTime.parse("2011-04-01T00:00:00.000+03:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-08T00:00:00.000+02:00"),None),
        Roadway(59736, 43166504,RoadPart(3821,1),AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Discontinuous,      4665,5430,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-30T00:00:00.000+03:00"),None),
        Roadway(70660,148121696,RoadPart(3821,1),AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Continuous,          222, 780,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-30T00:00:00.000+03:00"),None),
        Roadway(70589,148121784,RoadPart(3821,1),AdministrativeClass.Municipality,Track.Combined, Discontinuity.Continuous,         3905,4665,reversed = false,DateTime.parse("2011-04-01T00:00:00.000+03:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-08T00:00:00.000+02:00"),None),
        Roadway(72749,148127709,RoadPart(3821,2),AdministrativeClass.Municipality,Track.LeftSide, Discontinuity.MinorDiscontinuity,  507, 561,reversed = false,DateTime.parse("2018-07-01T00:00:00.000+03:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2018-07-05T00:00:00.000+03:00"),None),
        Roadway(72754,148127686,RoadPart(3821,2),AdministrativeClass.Municipality,Track.LeftSide, Discontinuity.Continuous,          812,1656,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-08T00:00:00.000+02:00"),None),
        Roadway(72949,148125051,RoadPart(3821,2),AdministrativeClass.Municipality,Track.Combined, Discontinuity.Continuous,         2190,2790,reversed = false,DateTime.parse("2005-06-30T00:00:00.000+03:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-08T00:00:00.000+02:00"),None),
        Roadway(72972,148125617,RoadPart(3821,1),AdministrativeClass.Municipality,Track.Combined, Discontinuity.Continuous,          909,1145,reversed = false,DateTime.parse("2011-04-01T00:00:00.000+03:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-08T00:00:00.000+02:00"),None),
        Roadway(72976,148125309,RoadPart(3821,2),AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Continuous,         1656,2190,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-30T00:00:00.000+03:00"),None),
        Roadway(72981,148124335,RoadPart(3821,1),AdministrativeClass.Municipality,Track.Combined, Discontinuity.Continuous,            0, 222,reversed = false,DateTime.parse("2011-04-01T00:00:00.000+03:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-08T00:00:00.000+02:00"),None),
        Roadway(73040,148125276,RoadPart(3821,1),AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Continuous,         2535,2802,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-30T00:00:00.000+03:00"),None),
        Roadway(72853,148125446,RoadPart(3821,1),AdministrativeClass.Municipality,Track.Combined, Discontinuity.Continuous,         1520,2535,reversed = false,DateTime.parse("2011-04-01T00:00:00.000+03:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-08T00:00:00.000+02:00"),None),
        Roadway(74788,148128101,RoadPart(3821,2),AdministrativeClass.Municipality,Track.LeftSide, Discontinuity.Continuous,         1656,2190,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-08T00:00:00.000+02:00"),None),
        Roadway(74641,148127970,RoadPart(3821,1),AdministrativeClass.Municipality,Track.LeftSide, Discontinuity.Continuous,         2535,2802,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-08T00:00:00.000+02:00"),None),
        Roadway(73562,148125036,RoadPart(3821,1),AdministrativeClass.Municipality,Track.Combined, Discontinuity.Continuous,         2802,3550,reversed = false,DateTime.parse("2011-04-01T00:00:00.000+03:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-08T00:00:00.000+02:00"),None),
        Roadway(73426,148124341,RoadPart(3821,1),AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Continuous,         3550,3905,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-30T00:00:00.000+03:00"),None),
        Roadway(73430,148124318,RoadPart(3821,2),AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Continuous,         2790,3020,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-30T00:00:00.000+03:00"),None),
        Roadway(74754,148128092,RoadPart(3821,2),AdministrativeClass.Municipality,Track.LeftSide, Discontinuity.Continuous,         2790,3020,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-08T00:00:00.000+02:00"),None),
        Roadway(73177,148127455,RoadPart(3821,1),AdministrativeClass.Municipality,Track.LeftSide, Discontinuity.Discontinuous,      4665,5430,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-08T00:00:00.000+02:00"),None),
        Roadway(73190,148127472,RoadPart(3821,2),AdministrativeClass.Municipality,Track.LeftSide, Discontinuity.MinorDiscontinuity, 4457,5188,reversed = false,DateTime.parse("2018-07-01T00:00:00.000+03:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2018-07-05T00:00:00.000+03:00"),None),
        Roadway(73235,148127824,RoadPart(3821,1),AdministrativeClass.Municipality,Track.LeftSide, Discontinuity.Continuous,          222, 780,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-08T00:00:00.000+02:00"),None),
        Roadway(74536,148128121,RoadPart(3821,1),AdministrativeClass.Municipality,Track.LeftSide, Discontinuity.Continuous,         1145,1520,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-08T00:00:00.000+02:00"),None),
        Roadway(73303,148122120,RoadPart(3821,2),AdministrativeClass.Municipality,Track.Combined, Discontinuity.Continuous,         3020,4457,reversed = false,DateTime.parse("2005-06-30T00:00:00.000+03:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-08T00:00:00.000+02:00"),None),
        Roadway(75204,148128250,RoadPart(3821,1),AdministrativeClass.Municipality,Track.LeftSide, Discontinuity.Continuous,         3550,3905,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-08T00:00:00.000+02:00"),None),
        Roadway(73606,148125555,RoadPart(3821,1),AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Continuous,         1145,1520,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-30T00:00:00.000+03:00"),None),
        Roadway(73607,148127711,RoadPart(3821,2),AdministrativeClass.Municipality,Track.LeftSide, Discontinuity.Continuous,            0, 507,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2016-03-08T00:00:00.000+02:00"),None),
        Roadway(81119,202230176,RoadPart(3821,2),AdministrativeClass.Municipality,Track.LeftSide, Discontinuity.MinorDiscontinuity,  561, 812,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2018-07-05T00:00:00.000+03:00"),None),
        Roadway(81129,202228103,RoadPart(3821,2),AdministrativeClass.Municipality,Track.RightSide,Discontinuity.MinorDiscontinuity, 5188,5651,reversed = false,DateTime.parse("2018-07-01T00:00:00.000+03:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2018-07-05T00:00:00.000+03:00"),None),
        Roadway(80988,202230183,RoadPart(3821,2),AdministrativeClass.Municipality,Track.RightSide,Discontinuity.MinorDiscontinuity,  561, 812,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2018-07-05T00:00:00.000+03:00"),None),
        Roadway(80697,202230193,RoadPart(3821,2),AdministrativeClass.Municipality,Track.LeftSide, Discontinuity.MinorDiscontinuity, 5188,5651,reversed = false,DateTime.parse("2018-07-01T00:00:00.000+03:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2018-07-05T00:00:00.000+03:00"),None),
        Roadway(81365,202230207,RoadPart(3821,2),AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Continuous,         5651,5708,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2018-07-05T00:00:00.000+03:00"),None),
        Roadway(80536,202230200,RoadPart(3821,2),AdministrativeClass.Municipality,Track.LeftSide, Discontinuity.Continuous,         5651,5708,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,3,TerminationCode.NoTermination,DateTime.parse("2018-07-05T00:00:00.000+03:00"),None)
      )

      val linearLocations = Seq(
        (743191, 1.0, 2618358, 0.0, 98.608, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(Some(4457),Some(RoadAddressCP)),CalibrationPointReference(None,None)), List(Point(567803.211,6770711.35,0.0), Point(567855.431,6770794.886,0.0)), LinkGeomSource.NormalLinkInterface, 148127472, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (574753, 2.0, 2618963, 0.0, 31.354, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(5708),Some(RoadAddressCP))), List(Point(568617.281,6771210.193,0.0), Point(568589.668,6771224.197,0.0)), LinkGeomSource.NormalLinkInterface, 202230200, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (574748, 1.0, 6929632, 0.0, 11.754, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(Some(5188),Some(RoadAddressCP)),CalibrationPointReference(Some(5199),Some(JunctionPointCP))), List(Point(568217.253,6771280.15,0.0), Point(568207.996,6771287.394,0.0)), LinkGeomSource.NormalLinkInterface, 202230193, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (706053, 2.0, 11140159, 0.0, 409.401, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(569219.082,6771105.294,0.0), Point(568816.54,6771179.281,0.0)), LinkGeomSource.NormalLinkInterface, 49024, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (756719, 11.0, 2621332, 0.0, 10.767, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564508.096,6769659.41,0.0), Point(564516.756,6769665.794,0.0)), LinkGeomSource.NormalLinkInterface, 79190, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (574747, 2.0, 6929630, 0.0, 9.466, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(Some(5199),Some(JunctionPointCP)),CalibrationPointReference(None,None)), List(Point(568207.996,6771287.394,0.0), Point(568202.955,6771294.85,0.0)), LinkGeomSource.NormalLinkInterface, 202230193, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (583467, 6.0, 2620635, 0.0, 63.029, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(565329.505,6770203.41,0.0), Point(565391.096,6770216.569,0.0)), LinkGeomSource.NormalLinkInterface, 6651364, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (756711, 2.0, 2621089, 0.0, 24.875, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564172.93,6769549.712,0.0), Point(564196.676,6769557.122,0.0)), LinkGeomSource.NormalLinkInterface, 79190, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (574755, 1.0, 2618948, 0.0, 21.242, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(Some(5651),Some(RoadAddressCP)),CalibrationPointReference(None,None)), List(Point(568565.985,6771209.469,0.0), Point(568586.942,6771210.011,0.0)), LinkGeomSource.NormalLinkInterface, 202230207, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (351769, 1.0, 2621175, 30.585, 83.861, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(561),Some(RoadAddressCP))), List(Point(564534.23,6769690.454,0.0), Point(564547.823,6769741.506,0.0)), LinkGeomSource.NormalLinkInterface, 79184, Some(DateTime.parse("2018-07-05T00:00:00.000+03:00")), None),
        (753914, 3.0, 6929628, 0.0, 9.37, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(Some(5213),Some(JunctionPointCP)),CalibrationPointReference(None,None)), List(Point(568214.732,6771292.557,0.0), Point(568209.675,6771300.445,0.0)), LinkGeomSource.NormalLinkInterface, 202228103, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (754558, 6.0, 2618374, 0.0, 112.356, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(4375),Some(JunctionPointCP))), List(Point(567713.195,6770541.615,0.0), Point(567758.927,6770644.055,0.0)), LinkGeomSource.NormalLinkInterface, 148122120, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (754650, 5.0, 2618183, 0.0, 55.645, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(568290.466,6771361.089,0.0), Point(568240.362,6771383.32,0.0)), LinkGeomSource.NormalLinkInterface, 202230193, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (743248, 3.0, 2621305, 0.0, 91.83, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564188.057,6769570.037,0.0), Point(564275.655,6769597.59,0.0)), LinkGeomSource.NormalLinkInterface, 148127711, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (743247, 6.0, 2621326, 0.0, 67.642, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564343.821,6769619.097,0.0), Point(564408.211,6769639.806,0.0)), LinkGeomSource.NormalLinkInterface, 148127711, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (756714, 3.0, 2621306, 0.0, 76.535, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564196.676,6769557.122,0.0), Point(564269.726,6769579.955,0.0)), LinkGeomSource.NormalLinkInterface, 79190, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (756712, 8.0, 2621337, 0.0, 63.921, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564351.01,6769605.741,0.0), Point(564411.946,6769625.046,0.0)), LinkGeomSource.NormalLinkInterface, 79190, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (743249, 5.0, 2621309, 0.0, 4.571, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564339.459,6769617.732,0.0), Point(564343.821,6769619.097,0.0)), LinkGeomSource.NormalLinkInterface, 148127711, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (754555, 2.0, 2618571, 0.0, 327.094, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(566995.698,6770160.081,0.0), Point(566705.487,6770308.363,0.0)), LinkGeomSource.NormalLinkInterface, 148122120, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (564163, 1.0, 10648023, 64.061, 233.576, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(565390.105,6770226.184,0.0), Point(565557.582,6770251.705,0.0)), LinkGeomSource.NormalLinkInterface, 148128101, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (564157, 3.0, 2618589, 0.0, 28.254, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(566620.18,6770362.463,0.0), Point(566595.988,6770376.558,0.0)), LinkGeomSource.NormalLinkInterface, 148128092, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (753913, 7.0, 2618173, 0.0, 39.387, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(568316.466,6771332.381,0.0), Point(568284.507,6771355.401,0.0)), LinkGeomSource.NormalLinkInterface, 202228103, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (754640, 1.0, 2621196, 0.0, 10.965, SideCode.TowardsDigitizing, 1634598047000L, (CalibrationPointReference(Some(561),Some(RoadAddressCP)),CalibrationPointReference(None,None)), List(Point(564547.578,6769759.089,0.0), Point(564547.676,6769770.054,0.0)), LinkGeomSource.NormalLinkInterface, 202230183, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (742394, 3.0, 2620680, 0.0, 239.642, SideCode.TowardsDigitizing, 1634598047000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(565583.716,6770242.816,0.0), Point(565820.814,6770277.457,0.0)), LinkGeomSource.NormalLinkInterface, 148125309, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (743242, 8.0, 2621323, 0.0, 92.672, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564419.431,6769643.519,0.0), Point(564506.506,6769674.274,0.0)), LinkGeomSource.NormalLinkInterface, 148127711, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (743244, 4.0, 2621314, 0.0, 66.909, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564275.655,6769597.59,0.0), Point(564339.459,6769617.732,0.0)), LinkGeomSource.NormalLinkInterface, 148127711, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (563293, 5.0, 6929633, 0.0, 8.487, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(Some(5179),Some(JunctionPointCP)),CalibrationPointReference(Some(5188),Some(RoadAddressCP))), List(Point(568207.996,6771287.394,0.0), Point(568214.345,6771292.261,0.0)), LinkGeomSource.NormalLinkInterface, 148127472, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (744060, 2.0, 2620700, 0.0, 27.864, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(565557.582,6770251.705,0.0), Point(565585.208,6770255.256,0.0)), LinkGeomSource.NormalLinkInterface, 148128101, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (754642, 4.0, 2620909, 0.0, 19.337, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564561.162,6769966.829,0.0), Point(564563.559,6769986.017,0.0)), LinkGeomSource.NormalLinkInterface, 202230183, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (743231, 1.0, 2620896, 0.0, 17.476, SideCode.TowardsDigitizing, 1634598047000L, (CalibrationPointReference(Some(812),Some(RoadAddressCP)),CalibrationPointReference(None,None)), List(Point(564584.524,6770035.876,0.0), Point(564601.614,6770039.528,0.0)), LinkGeomSource.NormalLinkInterface, 148127686, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (753912, 4.0, 6929627, 0.0, 49.032, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(568209.675,6771300.445,0.0), Point(568200.075,6771347.159,0.0)), LinkGeomSource.NormalLinkInterface, 202228103, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (564158, 5.0, 2618597, 0.0, 63.045, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(3020),Some(RoadAddressCP))), List(Point(566683.044,6770321.233,0.0), Point(566631.183,6770356.686,0.0)), LinkGeomSource.NormalLinkInterface, 148128092, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (756715, 7.0, 2621320, 0.0, 8.601, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564342.786,6769603.221,0.0), Point(564351.01,6769605.741,0.0)), LinkGeomSource.NormalLinkInterface, 79190, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (564160, 2.0, 12675141, 0.0, 34.254, SideCode.AgainstDigitizing, 1652734800000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(566595.776,6770376.698,0.0), Point(566567.36,6770395.367,0.0)), ComplementaryLinkInterface, 148128092, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (574754, 2.0, 2618964, 0.0, 30.459, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(5708),Some(RoadAddressCP))), List(Point(568587.184,6771210.003,0.0), Point(568617.148,6771210.075,0.0)), LinkGeomSource.NormalLinkInterface, 202230207, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (743234, 2.0, 2620884, 0.0, 249.621, SideCode.TowardsDigitizing, 1634598047000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564601.614,6770039.528,0.0), Point(564844.399,6770097.101,0.0)), LinkGeomSource.NormalLinkInterface, 148127686, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (574749, 7.0, 2618973, 0.0, 205.311, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(568507.394,6771257.095,0.0), Point(568320.8,6771337.798,0.0)), LinkGeomSource.NormalLinkInterface, 202230193, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (743230, 3.0, 2620784, 0.0, 155.167, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564844.399,6770097.101,0.0), Point(564995.227,6770133.5,0.0)), LinkGeomSource.NormalLinkInterface, 148127686, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (744061, 3.0, 2620681, 0.0, 236.094, SideCode.TowardsDigitizing, 1634598047000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(565585.208,6770255.256,0.0), Point(565819.046,6770286.572,0.0)), LinkGeomSource.NormalLinkInterface, 148128101, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (574744, 4.0, 2618179, 0.0, 62.471, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(568191.341,6771349.171,0.0), Point(568239.904,6771383.21,0.0)), LinkGeomSource.NormalLinkInterface, 202230193, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (574011, 8.0, 2618974, 0.0, 207.456, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(568498.281,6771235.056,0.0), Point(568316.466,6771332.381,0.0)), LinkGeomSource.NormalLinkInterface, 202228103, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (754560, 8.0, 2618361, 0.0, 56.683, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(4457),Some(RoadAddressCP))), List(Point(567771.643,6770664.271,0.0), Point(567803.211,6770711.35,0.0)), LinkGeomSource.NormalLinkInterface, 148122120, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (756718, 1.0, 2621083, 0.0, 105.305, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(Some(0),Some(RoadAddressCP)),CalibrationPointReference(None,None)), List(Point(564071.924,6769520.061,0.0), Point(564172.93,6769549.712,0.0)), LinkGeomSource.NormalLinkInterface, 79190, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (563344, 9.0, 2621176, 0.0, 22.565, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564506.506,6769674.274,0.0), Point(564522.441,6769690.074,0.0)), LinkGeomSource.NormalLinkInterface, 148127711, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (708099, 1.0, 2618357, 0.0, 99.048, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(Some(4457),Some(RoadAddressCP)),CalibrationPointReference(None,None)), List(Point(567803.211,6770711.35,0.0), Point(567864.089,6770789.104,0.0)), LinkGeomSource.NormalLinkInterface, 50308, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (574703, 5.0, 2618596, 0.0, 59.188, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(3020),Some(RoadAddressCP))), List(Point(566682.903,6770321.273,0.0), Point(566630.204,6770347.582,0.0)), LinkGeomSource.NormalLinkInterface, 148124318, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (744063, 4.0, 2618678, 0.0, 90.718, SideCode.TowardsDigitizing, 1634598047000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(2190),Some(RoadAddressCP))), List(Point(565819.046,6770286.572,0.0), Point(565908.092,6770303.342,0.0)), LinkGeomSource.NormalLinkInterface, 148128101, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (563296, 4.0, 6929618, 0.0, 177.61, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(5179),Some(JunctionPointCP))), List(Point(568098.755,6771148.171,0.0), Point(568207.996,6771287.394,0.0)), LinkGeomSource.NormalLinkInterface, 148127472, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (528201, 3.0, 2618153, 0.0, 166.64, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(568107.22,6771142.294,0.0), Point(568209.873,6771272.633,0.0)), LinkGeomSource.NormalLinkInterface, 50308, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (763365, 1.0, 2620897, 0.0, 18.744, SideCode.TowardsDigitizing, 1634598047000L, (CalibrationPointReference(Some(812),Some(RoadAddressCP)),CalibrationPointReference(None,None)), List(Point(564586.051,6770020.349,0.0), Point(564603.375,6770027.506,0.0)), LinkGeomSource.NormalLinkInterface, 6651364, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (754638, 5.0, 2620911, 0.0, 15.23, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(812),Some(RoadAddressCP))), List(Point(564563.559,6769986.017,0.0), Point(564564.707,6770001.204,0.0)), LinkGeomSource.NormalLinkInterface, 202230183, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (563342, 1.0, 2621176, 22.565, 75.545, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(561),Some(RoadAddressCP))), List(Point(564522.441,6769690.074,0.0), Point(564537.159,6769739.538,0.0)), LinkGeomSource.NormalLinkInterface, 148127709, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (574016, 9.0, 2618955, 0.0, 12.919, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(5651),Some(RoadAddressCP))), List(Point(568509.719,6771228.123,0.0), Point(568498.681,6771234.836,0.0)), LinkGeomSource.NormalLinkInterface, 202228103, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (743246, 1.0, 2621084, 0.0, 104.894, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(Some(0),Some(RoadAddressCP)),CalibrationPointReference(None,None)), List(Point(564063.384,6769533.617,0.0), Point(564164.119,6769562.655,0.0)), LinkGeomSource.NormalLinkInterface, 148127711, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (574017, 2.0, 2618186, 0.0, 12.661, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(Some(5199),Some(JunctionPointCP)),CalibrationPointReference(Some(5213),Some(JunctionPointCP))), List(Point(568217.253,6771280.15,0.0), Point(568214.732,6771292.557,0.0)), LinkGeomSource.NormalLinkInterface, 202228103, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (562440, 4.0, 2618480, 0.0, 133.577, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(2790),Some(RoadAddressCP))), List(Point(566482.601,6770432.12,0.0), Point(566351.082,6770439.097,0.0)), LinkGeomSource.NormalLinkInterface, 148125051, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (574701, 4.0, 2618604, 0.0, 14.931, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(566630.204,6770347.582,0.0), Point(566617.109,6770354.755,0.0)), LinkGeomSource.NormalLinkInterface, 148124318, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (754644, 6.0, 2618174, 0.0, 38.244, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(568320.8,6771337.798,0.0), Point(568290.466,6771361.089,0.0)), LinkGeomSource.NormalLinkInterface, 202230193, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (754557, 4.0, 2618392, 0.0, 105.993, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(Some(4136),Some(JunctionPointCP)),CalibrationPointReference(None,None)), List(Point(567657.912,6770430.601,0.0), Point(567706.521,6770524.419,0.0)), LinkGeomSource.NormalLinkInterface, 148122120, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (462093, 1.0, 2621199, 0.0, 10.459, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(Some(561),Some(RoadAddressCP)),CalibrationPointReference(None,None)), List(Point(564537.828,6769760.388,0.0), Point(564539.283,6769770.745,0.0)), LinkGeomSource.NormalLinkInterface, 202230176, Some(DateTime.parse("2018-07-05T00:00:00.000+03:00")), None),
        (562439, 1.0, 2618671, 0.0, 309.535, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(Some(2190),Some(RoadAddressCP)),CalibrationPointReference(None,None)), List(Point(565908.092,6770303.342,0.0), Point(566205.396,6770388.445,0.0)), LinkGeomSource.NormalLinkInterface, 148125051, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (754559, 5.0, 2618315, 0.0, 18.446, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(567706.521,6770524.419,0.0), Point(567713.195,6770541.615,0.0)), LinkGeomSource.NormalLinkInterface, 148122120, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (564156, 1.0, 2618584, 0.0, 93.474, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(Some(2790),Some(RoadAddressCP)),CalibrationPointReference(None,None)), List(Point(566566.945,6770395.596,0.0), Point(566482.601,6770432.12,0.0)), LinkGeomSource.NormalLinkInterface, 148128092, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (742395, 4.0, 2618679, 0.0, 91.243, SideCode.TowardsDigitizing, 1634598047000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(2190),Some(RoadAddressCP))), List(Point(565820.814,6770277.457,0.0), Point(565908.092,6770303.342,0.0)), LinkGeomSource.NormalLinkInterface, 148125309, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (756717, 9.0, 2621328, 0.0, 13.674, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564411.946,6769625.046,0.0), Point(564424.981,6769629.176,0.0)), LinkGeomSource.NormalLinkInterface, 79190, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (743232, 5.0, 2620726, 0.0, 335.841, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(565000.8,6770134.863,0.0), Point(565327.494,6770212.633,0.0)), LinkGeomSource.NormalLinkInterface, 148127686, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (562493, 1.0, 2620635, 63.029, 233.406, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(565391.096,6770216.569,0.0), Point(565559.534,6770239.421,0.0)), LinkGeomSource.NormalLinkInterface, 148125309, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (754553, 7.0, 2618378, 0.0, 23.905, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(Some(4375),Some(JunctionPointCP)),CalibrationPointReference(None,None)), List(Point(567758.927,6770644.055,0.0), Point(567771.643,6770664.271,0.0)), LinkGeomSource.NormalLinkInterface, 148122120, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (743233, 4.0, 2620798, 0.0, 5.737, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564995.227,6770133.5,0.0), Point(565000.8,6770134.863,0.0)), LinkGeomSource.NormalLinkInterface, 148127686, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (756720, 6.0, 2621311, 0.0, 50.696, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564294.486,6769587.821,0.0), Point(564342.786,6769603.221,0.0)), LinkGeomSource.NormalLinkInterface, 79190, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (563330, 6.0, 10648023, 0.0, 64.061, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(565327.494,6770212.633,0.0), Point(565390.046,6770226.171,0.0)), LinkGeomSource.NormalLinkInterface, 148127686, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (706054, 4.0, 11275750, 0.0, 72.467, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(6404),Some(JunctionPointCP))), List(Point(569298.536,6771087.834,0.0), Point(569227.758,6771103.389,0.0)), LinkGeomSource.NormalLinkInterface, 49024, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (743245, 7.0, 2621329, 0.0, 11.818, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564408.211,6769639.806,0.0), Point(564419.431,6769643.519,0.0)), LinkGeomSource.NormalLinkInterface, 148127711, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (574750, 3.0, 2618159, 0.0, 57.38, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(568202.694,6771295.236,0.0), Point(568191.221,6771348.81,0.0)), LinkGeomSource.NormalLinkInterface, 202230193, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (763364, 5.0, 2620729, 0.0, 335.083, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(565004.057,6770123.957,0.0), Point(565329.505,6770203.41,0.0)), LinkGeomSource.NormalLinkInterface, 6651364, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (574752, 1.0, 2618945, 0.0, 23.343, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(Some(5651),Some(RoadAddressCP)),CalibrationPointReference(None,None)), List(Point(568589.408,6771224.42,0.0), Point(568572.459,6771239.949,0.0)), LinkGeomSource.NormalLinkInterface, 202230200, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (526153, 1.0, 2618918, 0.0, 201.305, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(Some(5708),Some(RoadAddressCP)),CalibrationPointReference(None,None)), List(Point(568816.238,6771179.327,0.0), Point(568617.607,6771210.055,0.0)), LinkGeomSource.NormalLinkInterface, 49024, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (574012, 6.0, 2618182, 0.0, 48.654, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(568284.507,6771355.401,0.0), Point(568239.515,6771372.293,0.0)), LinkGeomSource.NormalLinkInterface, 202228103, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (763362, 3.0, 2620785, 0.0, 155.679, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564846.183,6770086.623,0.0), Point(564997.683,6770122.45,0.0)), LinkGeomSource.NormalLinkInterface, 6651364, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (742393, 2.0, 2620697, 0.0, 24.014, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(565559.937,6770239.468,0.0), Point(565583.716,6770242.816,0.0)), LinkGeomSource.NormalLinkInterface, 148125309, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (562441, 2.0, 2618703, 0.0, 135.096, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(566205.396,6770388.445,0.0), Point(566332.427,6770434.134,0.0)), LinkGeomSource.NormalLinkInterface, 148125051, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (562438, 3.0, 2618716, 0.0, 19.208, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(566332.517,6770434.167,0.0), Point(566350.881,6770439.044,0.0)), LinkGeomSource.NormalLinkInterface, 148125051, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (756721, 12.0, 2621175, 0.0, 30.585, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564516.756,6769665.794,0.0), Point(564534.23,6769690.454,0.0)), LinkGeomSource.NormalLinkInterface, 79190, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (574704, 1.0, 2618583, 0.0, 91.162, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(Some(2790),Some(RoadAddressCP)),CalibrationPointReference(None,None)), List(Point(566561.533,6770386.961,0.0), Point(566482.601,6770432.12,0.0)), LinkGeomSource.NormalLinkInterface, 148124318, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (743193, 2.0, 2618335, 0.0, 367.046, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(567855.431,6770794.886,0.0), Point(568063.083,6771097.483,0.0)), LinkGeomSource.NormalLinkInterface, 148127472, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (763363, 4.0, 2620802, 0.0, 6.55, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564997.683,6770122.45,0.0), Point(565004.057,6770123.957,0.0)), LinkGeomSource.NormalLinkInterface, 6651364, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (706055, 3.0, 11142353, 0.0, 8.883, SideCode.AgainstDigitizing, 1635808409000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(569227.758,6771103.389,0.0), Point(569219.082,6771105.294,0.0)), LinkGeomSource.NormalLinkInterface, 49024, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (743194, 3.0, 2618344, 0.0, 61.982, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(568063.083,6771097.483,0.0), Point(568098.755,6771148.171,0.0)), LinkGeomSource.NormalLinkInterface, 148127472, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (574010, 1.0, 2618153, 166.64, 177.175, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(5199),Some(JunctionPointCP))), List(Point(568209.873,6771272.633,0.0), Point(568217.253,6771280.15,0.0)), LinkGeomSource.NormalLinkInterface, 202228103, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (754556, 3.0, 2618435, 0.0, 754.015, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(4136),Some(JunctionPointCP))), List(Point(566995.698,6770160.081,0.0), Point(567657.912,6770430.601,0.0)), LinkGeomSource.NormalLinkInterface, 148122120, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (574705, 3.0, 2618600, 0.0, 28.861, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(566617.109,6770354.755,0.0), Point(566591.789,6770368.606,0.0)), LinkGeomSource.NormalLinkInterface, 148124318, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (756716, 4.0, 2621315, 0.0, 11.151, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564269.726,6769579.955,0.0), Point(564280.309,6769583.47,0.0)), LinkGeomSource.NormalLinkInterface, 79190, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (708098, 2.0, 2618337, 0.0, 428.813, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(567864.089,6770789.104,0.0), Point(568107.22,6771142.294,0.0)), LinkGeomSource.NormalLinkInterface, 50308, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (754554, 1.0, 2618610, 0.0, 25.827, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(Some(3020),Some(RoadAddressCP)),CalibrationPointReference(None,None)), List(Point(566705.487,6770308.363,0.0), Point(566683.078,6770321.204,0.0)), LinkGeomSource.NormalLinkInterface, 148122120, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (462096, 4.0, 2620912, 0.0, 17.014, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(812),Some(RoadAddressCP))), List(Point(564554.132,6769986.065,0.0), Point(564552.128,6770002.918,0.0)), LinkGeomSource.NormalLinkInterface, 202230176, Some(DateTime.parse("2018-07-05T00:00:00.000+03:00")), None),
        (574746, 8.0, 2618942, 0.0, 13.458, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(5651),Some(RoadAddressCP))), List(Point(568520.493,6771254.735,0.0), Point(568507.698,6771257.029,0.0)), LinkGeomSource.NormalLinkInterface, 202230193, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (754641, 2.0, 2621223, 0.0, 90.539, SideCode.TowardsDigitizing, 1634598047000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564547.676,6769770.054,0.0), Point(564553.319,6769860.417,0.0)), LinkGeomSource.NormalLinkInterface, 202230183, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (763361, 2.0, 2620883, 0.0, 249.906, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564603.375,6770027.506,0.0), Point(564846.183,6770086.623,0.0)), LinkGeomSource.NormalLinkInterface, 6651364, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (462095, 3.0, 2621210, 0.0, 167.07, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564542.894,6769819.375,0.0), Point(564554.132,6769986.065,0.0)), LinkGeomSource.NormalLinkInterface, 202230176, Some(DateTime.parse("2018-07-05T00:00:00.000+03:00")), None),
        (574702, 2.0, 12675142, 0.0, 35.227, SideCode.AgainstDigitizing, 1652734800000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(566591.595,6770368.723,0.0), Point(566561.67,6770386.875,0.0)), ComplementaryLinkInterface, 148124318, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (526157, 5.0, 2618768, 0.0, 417.78, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(Some(6404),Some(JunctionPointCP)),CalibrationPointReference(Some(6826),Some(RoadAddressCP))), List(Point(569685.205,6770938.98,0.0), Point(569298.536,6771087.834,0.0)), LinkGeomSource.NormalLinkInterface, 49024, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (756710, 5.0, 2621318, 0.0, 14.83, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564280.309,6769583.47,0.0), Point(564294.486,6769587.821,0.0)), LinkGeomSource.NormalLinkInterface, 79190, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (753908, 5.0, 2618178, 0.0, 48.914, SideCode.TowardsDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(568200.075,6771347.159,0.0), Point(568239.515,6771372.293,0.0)), LinkGeomSource.NormalLinkInterface, 202228103, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (564159, 4.0, 2618605, 0.0, 12.173, SideCode.AgainstDigitizing, 1630018852000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(566631.03,6770356.766,0.0), Point(566620.401,6770362.337,0.0)), LinkGeomSource.NormalLinkInterface, 148128092, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (754639, 3.0, 2620906, 0.0, 106.701, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564553.319,6769860.417,0.0), Point(564561.162,6769966.829,0.0)), LinkGeomSource.NormalLinkInterface, 202230183, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (743250, 2.0, 2621085, 0.0, 25.05, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564164.119,6769562.655,0.0), Point(564188.057,6769570.037,0.0)), LinkGeomSource.NormalLinkInterface, 148127711, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (756713, 10.0, 2621322, 0.0, 88.702, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564424.981,6769629.176,0.0), Point(564508.096,6769659.41,0.0)), LinkGeomSource.NormalLinkInterface, 79190, Some(DateTime.parse("2022-06-15T00:00:00.000+03:00")), None),
        (462094, 2.0, 2621184, 0.0, 48.764, SideCode.TowardsDigitizing, 1533863903000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(564539.283,6769770.745,0.0), Point(564542.894,6769819.375,0.0)), LinkGeomSource.NormalLinkInterface, 202230176, Some(DateTime.parse("2018-07-05T00:00:00.000+03:00")), None)
      ).map(l => LinearLocation(l._1,l._2,l._3.toString,l._4,l._5,l._6,l._7,l._8,l._9,l._10,l._11,l._12,l._13))

      buildTestDataForProject(Some(project), Some(roadways), Some(linearLocations), Some(projectLinks))

      val calculated = defaultSectionCalculatorStrategy.assignMValues(Seq(), projectLinks.filterNot(_.status == RoadAddressChangeType.Termination), Seq.empty[UserDefinedCalibrationPoint])
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

      val Road1TransferLeftLink       = ProjectLink(plId + 1, RoadPart(9999, 1), Track.LeftSide,  Discontinuity.Continuous,   0L,  10L, 890L, 900L, None, None, None, 12345L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomRoad1TransferLeft,       0L, RoadAddressChangeType.Transfer,   AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRoad1TransferLeft),       0L, 0, 0, reversed = false, None, 86400L)
      val Road1TransferRightLink      = ProjectLink(plId + 2, RoadPart(9999, 1), Track.RightSide, Discontinuity.Continuous, 890L, 900L, 890L, 900L, None, None, None, 12346L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomRoad1TransferRight,      0L, RoadAddressChangeType.Transfer,   AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRoad1TransferRight),      0L, 0, 0, reversed = false, None, 86400L)
      val Road2NotHandledLeftLink     = ProjectLink(plId + 3, RoadPart(9999, 1), Track.LeftSide,  Discontinuity.Continuous,   0L,  10L,   0L,  10L, None, None, None, 12345L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomRoad2NotHandledLeft,     0L, RoadAddressChangeType.NotHandled, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRoad2NotHandledLeft),     0L, 0, 0, reversed = false, None, 86400L)
      val Road2NotHandledRightLink    = ProjectLink(plId + 4, RoadPart(9999, 1), Track.RightSide, Discontinuity.Continuous,   0L,  10L,   0L,  10L, None, None, None, 12346L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomRoad2NotHandledRight,    0L, RoadAddressChangeType.NotHandled, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRoad2NotHandledRight),    0L, 0, 0, reversed = false, None, 86400L)
      val Road2NotHandledCombinedLink = ProjectLink(plId + 4, RoadPart(9999, 1), Track.Combined,  Discontinuity.Continuous,  10L,  20L,  10L,  20L, None, None, None, 12346L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomRoad2NotHandledCombined, 0L, RoadAddressChangeType.NotHandled, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRoad2NotHandledCombined), 0L, 0, 0, reversed = false, None, 86400L)


      val otherProjectLinks = Seq(Road2NotHandledRightLink, Road2NotHandledLeftLink, Road1TransferLeftLink, Road2NotHandledCombinedLink, Road1TransferRightLink)
      val newProjectLinks = Seq()

      val startingPointsForCalculations = defaultSectionCalculatorStrategy.findStartingPoints(newProjectLinks, otherProjectLinks, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
      startingPointsForCalculations should be((Road1TransferRightLink.geometry.head, Road1TransferLeftLink.geometry.head))
    }
  }

  test("Test findStartingPoints When terminating first links on two track and transferring the remaining links on two track road Then the road should still maintain the previous existing direction") {
    /**
     * ------L Transf--------> 100
     * ^                      ^
     * |                      |
     * |                      |
     * L Term                 R Transf
     * |                      |
     * |                      |
     * |                      |
     * 0-------R Term-------->
     * */
    runWithRollback {

      val geomTerminatedLeftLink = Seq(Point(25.0,25.0), Point(25.0,75.0))
      val geomTransferredLeftLink = Seq(Point(25.0, 75.0), Point(75.0, 75.0))

      val geomTerminatedRightLink = Seq(Point(25.0,25.0), Point(75.0, 25.0))
      val geomTransferredRightLink = Seq(Point(75.0, 25.0), Point(75.0, 75.0))

      val plId = Sequences.nextProjectLinkId

      // NOTE: The terminated links are not used in the start point calculation, but they are displayed here to aid in understanding the test case.
      val terminatedLeftLink = ProjectLink(plId + 1, 9999L, 1L, Track.LeftSide, Discontinuity.Continuous, 0L, 50L, 0L, 50L, None, None, None, 12345L.toString, 0.0, 50.0, SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP), geomTerminatedLeftLink, 0L, RoadAddressChangeType.Termination, AdministrativeClass.State,LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTerminatedLeftLink),0L, 0, 0, reversed = false, None, 86400L)
      val terminatedRightLink = ProjectLink(plId + 1, 9999L, 1L, Track.RightSide, Discontinuity.Continuous, 0L, 50L, 0L, 50L, None, None, None, 12345L.toString, 0.0, 50.0, SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP), geomTerminatedRightLink, 0L, RoadAddressChangeType.Termination, AdministrativeClass.State,LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTerminatedRightLink),0L, 0, 0, reversed = false, None, 86400L)

      val transferredLeftLink = ProjectLink(plId + 1, 9999L, 1L, Track.LeftSide, Discontinuity.EndOfRoad, 50L, 100L, 50L, 100L, None, None, None, 12345L.toString, 0.0, 50.0, SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP), geomTransferredLeftLink, 0L, RoadAddressChangeType.Transfer, AdministrativeClass.State,LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferredLeftLink),0L, 0, 0, reversed = false, None, 86400L)
      val transferredRightLink = ProjectLink(plId + 1, 9999L, 1L, Track.RightSide, Discontinuity.EndOfRoad, 50L, 100L, 50L, 100L, None, None, None, 12345L.toString, 0.0, 50.0, SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP), geomTransferredRightLink, 0L, RoadAddressChangeType.Transfer, AdministrativeClass.State,LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferredRightLink),0L, 0, 0, reversed = false, None, 86400L)

      val nonTerminatedProjectLinks = Seq(transferredRightLink,transferredLeftLink)

      val newProjectLinks = Seq()

      val startingPointsForCalculations = defaultSectionCalculatorStrategy.findStartingPoints(newProjectLinks, nonTerminatedProjectLinks, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
      startingPointsForCalculations should be((transferredRightLink.geometry.head, transferredLeftLink.geometry.head))
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
        ProjectLink(plId + 1, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous,    0L, 0L, 0L, 0L, None, None, None, 12345L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geom1, 0L, RoadAddressChangeType.Transfer, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom1), 0L, 0, 0, reversed = true, None, 86400L),
        ProjectLink(plId + 2, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous,    0L, 0L, 0L, 0L, None, None, None, 12346L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geom2, 0L, RoadAddressChangeType.Transfer, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom2), 0L, 0, 0, reversed = true, None, 86400L),
        ProjectLink(plId + 3, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous,    0L, 0L, 0L, 0L, None, None, None, 12347L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geom3, 0L, RoadAddressChangeType.Transfer, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom3), 0L, 0, 0, reversed = true, None, 86400L),
        ProjectLink(plId + 4, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous,    0L, 0L, 0L, 0L, None, None, None, 12348L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geom4, 0L, RoadAddressChangeType.Transfer, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom4), 0L, 0, 0, reversed = true, None, 86400L),
        ProjectLink(plId + 4, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous,    0L, 0L, 0L, 0L, None, None, None, 12349L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geom5, 0L, RoadAddressChangeType.Transfer, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom5), 0L, 0, 0, reversed = true, None, 86400L),
        ProjectLink(plId + 5, RoadPart(9999, 1), Track.Combined, Discontinuity.Discontinuous, 0L, 0L, 0L, 0L, None, None, None, 12350L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geom6, 0L, RoadAddressChangeType.Transfer, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom6), 0L, 0, 0, reversed = true, None, 86400L)
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

      val projectLink1 = ProjectLink(plId + 1, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous,  0L, 15L, 15L, 30L, None, None, None, 12345L.toString, 0.0, 15.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransfer1, 0L, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransfer1), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLink2 = ProjectLink(plId + 2, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, 15L, 30L, 30L, 45L, None, None, None, 12346L.toString, 0.0, 15.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransfer2, 0L, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransfer2), 0L, 0, 0, reversed = false, None, 86400L)

      val geomNew3 = Seq(Point(40.0, 10.0), Point(30.0, 20.0))

      val projectLinkNew3 = ProjectLink(plId + 3, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12347L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomNew3, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew3), 0L, 0, 0, reversed = false, None, 86400L)

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

      val projectLink1 = ProjectLink(plId + 1, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous,  0L, 15L, 15L, 30L, None, None, None, 12345L.toString, 0.0, 15.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransfer1, 0L, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransfer1), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLink2 = ProjectLink(plId + 2, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, 15L, 30L, 30L, 45L, None, None, None, 12346L.toString, 0.0, 15.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransfer2, 0L, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransfer2), 0L, 0, 0, reversed = false, None, 86400L)

      val geomNew3 = Seq(Point(30.0, 20.0), Point(40.0, 10.0))

      val projectLinkNew3 = ProjectLink(plId + 3, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12347L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomNew3, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew3), 0L, 0, 0, reversed = false, None, 86400L)

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
  private def testFindStartingPointsWithOneMinorDiscontinuity(sideCode: SideCode, roadAddressChangeType: RoadAddressChangeType): Unit = {
    runWithRollback {
      val geom1 = Seq(Point(10.0, 20.0), Point(0.0, 30.0))
      val plId = Sequences.nextProjectLinkId

      val projectLink1 = ProjectLink(plId + 1, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, 0L, 15L, 0L, 15L, None, None, None, 12345L.toString, 0.0, 15.0, sideCode, (NoCP, NoCP), (NoCP, NoCP), geom1, 0L, roadAddressChangeType, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom1), 0L, 0, 0, reversed = false, None, 86400L)

      val geomNew2 = Seq(Point(30.0, 0.0), Point(20.0, 10.0))

      // Notice that discontinuity value should not affect calculations, which are based on geometry. That's why we have here this value "Continuous".
      val projectLinkNew2 = ProjectLink(plId + 3, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12347L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomNew2, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew2), 0L, 0, 0, reversed = false, None, 86400L)

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
    testFindStartingPointsWithOneMinorDiscontinuity(SideCode.TowardsDigitizing, RoadAddressChangeType.New)
  }

  /*
         \  <- #1 New
             (minor discontinuity)
           \  <- #2 New
            v
   */
  test("Test findStartingPoints When adding one (New) link with minor discontinuity after the existing (New) road (against digitization) Then the road should still maintain the previous existing direction") {
    testFindStartingPointsWithOneMinorDiscontinuity(SideCode.AgainstDigitizing, RoadAddressChangeType.New)
  }

  /*
        ^
         \  <- #1 Transfer
             (minor discontinuity)
           \  <- #2 New
   */
  test("Test findStartingPoints When adding one (New) link with minor discontinuity before the existing (Transfer) road Then the road should still maintain the previous existing direction") {
    testFindStartingPointsWithOneMinorDiscontinuity(SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer)
  }

  /*
         \  <- #1 Transfer
             (minor discontinuity)
           \  <- #2 New
            v
   */
  test("Test findStartingPoints When adding one (New) link with minor discontinuity after the existing (Transfer) road (against digitization) Then the road should still maintain the previous existing direction") {
    testFindStartingPointsWithOneMinorDiscontinuity(SideCode.AgainstDigitizing, RoadAddressChangeType.Transfer)
  }

  /*
        ^
         \  <- #1 NotHandled
             (minor discontinuity)
           \  <- #2 New
   */
  test("Test findStartingPoints When adding one (New) link with minor discontinuity before the existing (NotHandled) road Then the road should still maintain the previous existing direction") {
    testFindStartingPointsWithOneMinorDiscontinuity(SideCode.TowardsDigitizing, RoadAddressChangeType.NotHandled)
  }

  /*
         \  <- #1 NotHandled
             (minor discontinuity)
           \  <- #2 New
            v
   */
  test("Test findStartingPoints When adding one (New) link with minor discontinuity after the existing (NotHandled) road (against digitization) Then the road should still maintain the previous existing direction") {
    testFindStartingPointsWithOneMinorDiscontinuity(SideCode.AgainstDigitizing, RoadAddressChangeType.NotHandled)
  }

  /*
         \  <- #1
             (minor discontinuity)
           \  <- #2
               (minor discontinuity)
             \  <- #3
   */
  private def testFindStartingPointsWithTwoMinorDiscontinuitiesNewInMiddle(sideCode: SideCode, roadAddressChangeType: RoadAddressChangeType): Unit = {
    runWithRollback {
      val geom1 = Seq(Point(10.0, 40.0), Point(0.0, 50.0))
      val plId = Sequences.nextProjectLinkId

      val projectLink1 = ProjectLink(plId + 1, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, 0L, 15L, 0L, 15L, None, None, None, 12345L.toString, 0.0, 15.0, sideCode, (NoCP, NoCP), (NoCP, NoCP), geom1, 0L, roadAddressChangeType, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom1), 0L, 0, 0, reversed = false, None, 86400L)

      val geomNew2 = Seq(Point(30.0, 20.0), Point(20.0, 30.0))
      val geomNew3 = Seq(Point(50.0, 0.0), Point(40.0, 10.0))

      // Notice that discontinuity value should not affect calculations, which are based on geometry. That's why we have here this value "Continuous".
      val projectLinkNew2 = ProjectLink(plId + 2, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12347L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomNew2, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew2), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkNew3 = ProjectLink(plId + 3, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12347L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomNew3, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew3), 0L, 0, 0, reversed = false, None, 86400L)

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
    testFindStartingPointsWithTwoMinorDiscontinuitiesNewInMiddle(SideCode.TowardsDigitizing, RoadAddressChangeType.New)
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
    testFindStartingPointsWithTwoMinorDiscontinuitiesNewInMiddle(SideCode.AgainstDigitizing, RoadAddressChangeType.New)
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
    testFindStartingPointsWithTwoMinorDiscontinuitiesNewInMiddle(SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer)
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
    testFindStartingPointsWithTwoMinorDiscontinuitiesNewInMiddle(SideCode.AgainstDigitizing, RoadAddressChangeType.Transfer)
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
    testFindStartingPointsWithTwoMinorDiscontinuitiesNewInMiddle(SideCode.TowardsDigitizing, RoadAddressChangeType.NotHandled)
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
    testFindStartingPointsWithTwoMinorDiscontinuitiesNewInMiddle(SideCode.AgainstDigitizing, RoadAddressChangeType.NotHandled)
  }

  /*
       \  <- #1
           (minor discontinuity)
         \  <- #2
             (minor discontinuity)
          \ \  <- #3/#4
   */
  private def testFindStartingPointsWithTwoMinorDiscontinuitiesNewInMiddleTwoTrackOnOtherSide(sideCode: SideCode, roadAddressChangeType: RoadAddressChangeType): Unit = {
    runWithRollback {
      val geom1 = Seq(Point(10.0, 40.0), Point(0.0, 50.0))
      val geom3 = Seq(Point(45.0, 0.0), Point(35.0, 10.0))
      val geom4 = Seq(Point(55.0, 0.0), Point(45.0, 10.0))
      val plId = Sequences.nextProjectLinkId

      val startAddr1 = if (sideCode == SideCode.TowardsDigitizing) 15L else 0
      val endAddr1 = if (sideCode == SideCode.TowardsDigitizing) 30L else 15L
      val projectLink1 = ProjectLink(plId + 1, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, startAddr1, endAddr1, startAddr1, endAddr1, None, None, None, 12345L.toString, 0.0, 15.0, sideCode, (NoCP, NoCP), (NoCP, NoCP), geom1, 0L, roadAddressChangeType, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom1), 0L, 0, 0, reversed = false, None, 86400L)

      val startAddr34 = if (sideCode == SideCode.TowardsDigitizing) 0L else 15L
      val endAddr34 = if (sideCode == SideCode.TowardsDigitizing) 15L else 30L
      val projectLinkNew3 = ProjectLink(plId + 3, RoadPart(9999, 1), if (sideCode == SideCode.TowardsDigitizing) Track.LeftSide else Track.RightSide, Discontinuity.Continuous, startAddr34, endAddr34, startAddr34, endAddr34, None, None, None, 12347L.toString, 0.0, 0.0, sideCode, (NoCP, NoCP), (NoCP, NoCP), geom3, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom3), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkNew4 = ProjectLink(plId + 4, RoadPart(9999, 1), if (sideCode == SideCode.TowardsDigitizing) Track.RightSide else Track.LeftSide, Discontinuity.Continuous, startAddr34, endAddr34, startAddr34, endAddr34, None, None, None, 12348L.toString, 0.0, 0.0, sideCode, (NoCP, NoCP), (NoCP, NoCP), geom4, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom4), 0L, 0, 0, reversed = false, None, 86400L)

      val geomNew2 = Seq(Point(30.0, 20.0), Point(20.0, 30.0))

      // Notice that discontinuity value should not affect calculations, which are based on geometry. That's why we have here this value "Continuous".
      val projectLinkNew2 = ProjectLink(plId + 2, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12346L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomNew2, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew2), 0L, 0, 0, reversed = false, None, 86400L)

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
    testFindStartingPointsWithTwoMinorDiscontinuitiesNewInMiddleTwoTrackOnOtherSide(SideCode.TowardsDigitizing, RoadAddressChangeType.NotHandled)
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
    testFindStartingPointsWithTwoMinorDiscontinuitiesNewInMiddleTwoTrackOnOtherSide(SideCode.AgainstDigitizing, RoadAddressChangeType.NotHandled)
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
    testFindStartingPointsWithTwoMinorDiscontinuitiesNewInMiddleTwoTrackOnOtherSide(SideCode.TowardsDigitizing, RoadAddressChangeType.New)
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
    testFindStartingPointsWithTwoMinorDiscontinuitiesNewInMiddleTwoTrackOnOtherSide(SideCode.AgainstDigitizing, RoadAddressChangeType.New)
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
    testFindStartingPointsWithTwoMinorDiscontinuitiesNewInMiddleTwoTrackOnOtherSide(SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer)
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
    testFindStartingPointsWithTwoMinorDiscontinuitiesNewInMiddleTwoTrackOnOtherSide(SideCode.AgainstDigitizing, RoadAddressChangeType.Transfer)
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

      val projectLink1 = ProjectLink(plId + 1, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, 0L, 15L, 0L, 15L, None, None, None, 12345L.toString, 0.0, 15.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransfer1, 0L, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransfer1), 0L, 0, 0, reversed = false, None, 86400L)

      val geomNew2 = Seq(Point(35.0, 0.0), Point(25.0, 10.0))
      val geomNew3 = Seq(Point(25.0, 0.0), Point(15.0, 10.0))

      val projectLinkNew2 = ProjectLink(plId + 2, RoadPart(9999, 1), Track.RightSide, Discontinuity.MinorDiscontinuity, 0L, 0L, 0L, 0L, None, None, None, 12347L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomNew2, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew2), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkNew3 = ProjectLink(plId + 3, RoadPart(9999, 1), Track.LeftSide,  Discontinuity.MinorDiscontinuity, 0L, 0L, 0L, 0L, None, None, None, 12347L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomNew3, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew3), 0L, 0, 0, reversed = false, None, 86400L)


      val otherProjectLinks = Seq(projectLink1)
      val newProjectLinks = Seq(projectLinkNew2, projectLinkNew3)

      val startingPointsForCalculations = defaultSectionCalculatorStrategy.findStartingPoints(newProjectLinks, otherProjectLinks, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
      startingPointsForCalculations should be((geomNew2.head, geomNew3.head))
    }
  }

  test("Test findStartingPoints When creating New Left track link which have its geometry reversed and Transfer Combined to RightSide before existing NotHandled link road Then the road should still maintain the previous existing direction") {
    runWithRollback {
      val plId = Sequences.nextProjectLinkId

      val geomNotHandled1   = Seq(Point(10.0, 5.0), Point(20.0, 5.0))
      val geomNewLeft       = Seq(Point(10.0, 5.0), Point(0.0, 10.0))
      val geomTransferRight = Seq(Point( 0.0, 0.0), Point(10.0, 5.0))

      val projectLink1        = ProjectLink(plId + 1, RoadPart(9999, 1), Track.Combined,  Discontinuity.Continuous, 10L, 25L, 10L, 25L, None, None, None, 12345L.toString, 0.0, 15.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNotHandled1,   0L, RoadAddressChangeType.NotHandled, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNotHandled1),   0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkTransfer = ProjectLink(plId + 2, RoadPart(9999, 1), Track.RightSide, Discontinuity.Continuous,  0L, 10L,  0L, 10L, None, None, None, 12347L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferRight, 0L, RoadAddressChangeType.Transfer,   AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkNew      = ProjectLink(plId + 3, RoadPart(9999, 1), Track.LeftSide,  Discontinuity.Continuous,  0L,  0L,  0L,  0L, None, None, None, 12347L.toString, 0.0, 10.0, SideCode.Unknown,           (NoCP, NoCP), (NoCP, NoCP), geomNewLeft,       0L, RoadAddressChangeType.New,        AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewLeft),       0L, 0, 0, reversed = false, None, 86400L)

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

      val projectLinkComb1 = ProjectLink(plId + 1, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, 15L, 30L, 15L, 30L, None, None, None, 12345L.toString, 0.0, 15.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferComb1, 0L, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferComb1), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkComb2 = ProjectLink(plId + 2, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, 30L, 45L, 30L, 45L, None, None, None, 12346L.toString, 0.0, 15.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferComb2, 0L, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferComb2), 0L, 0, 0, reversed = false, None, 86400L)

      val geomNewCombBefore = Seq(Point(50.0, 20.0), Point(40.0, 30.0))
      val geomNewCombAfter = Seq(Point(10.0, 60.0), Point(20.0, 60.0))

      val projectLinkCombNewBefore = ProjectLink(plId + 3, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12347L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomNewCombBefore, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewCombBefore), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkCombNewAfter  = ProjectLink(plId + 4, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12347L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomNewCombAfter,  0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewCombAfter),  0L, 0, 0, reversed = false, None, 86400L)

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
//      val projectLinkLeft1 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(2), Discontinuity.Continuous,          0L,  5L, 0L,  5L, None, None, None, 12345L, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferLeft1, projectId, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferLeft1), roadwayId,     linearLocationId,     8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber)
//      val projectLinkLeft2 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(2), Discontinuity.ParallelLink,        5L, 10L, 5L, 10L, None, None, None, 12346L, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferLeft2, projectId, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferLeft2), roadwayId + 1, linearLocationId + 1, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber)
//      //Right New
//      val projectLinkRight1 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(1), Discontinuity.Continuous,         0L,  0L, 0L,  0L, None, None, None, 12347L, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNewRight1,     projectId, RoadAddressChangeType.New,      AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewRight1), 0, 0, 8L, reversed = false, None, 86400L)
//      val projectLinkRight2 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(1), Discontinuity.MinorDiscontinuity, 0L,  0L, 0L,  0L, None, None, None, 12348L, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNewRight2,     projectId, RoadAddressChangeType.New,      AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewRight2), 0, 0, 8L, reversed = false, None, 86400L)
//
//      //after roundabout
//
//      //Left New
//      val projectLinkLeft3  = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(2), Discontinuity.Continuous, 0L,  0L,  0L,  0L, None, None, None, 12349L, 0.0,  5.1, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferLeft3,  projectId, RoadAddressChangeType.New,      AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferLeft3), 0, 0, 8L, reversed = false, None, 86400L)
//      val projectLinkLeft4  = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(2), Discontinuity.Continuous, 0L,  0L,  0L,  0L, None, None, None, 12350L, 0.0,  5.3, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferLeft4,  projectId, RoadAddressChangeType.New,      AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferLeft4), 0, 0, 8L, reversed = false, None, 86400L)
//      val projectLinkLeft5  = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(2), Discontinuity.Continuous, 0L,  0L,  0L,  0L, None, None, None, 12351L, 0.0, 10.1, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferLeft5,  projectId, RoadAddressChangeType.New,      AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferLeft5), 0, 0, 8L, reversed = false, None, 86400L)
//      //Right Transfer
//      val projectLinkRight3 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(1), Discontinuity.Continuous, 0L,  5L,  0L,  5L, None, None, None, 12352L, 0.0,  5.1, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferRight3, projectId, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight3), roadwayId + 2, linearLocationId + 2, 8L, reversed = false, None, 86400L, roadwayNumber = Sequences.nextRoadwayNumber)
//      val nextRwNumber = Sequences.nextRoadwayNumber
//      val projectLinkRight4 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(1), Discontinuity.Continuous, 5L, 10L,  5L, 10L, None, None, None, 12352L, 0.0,  5.1, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferRight4, projectId, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight4), roadwayId + 3, linearLocationId + 3, 8L, reversed = false, None, 86400L, roadwayNumber = nextRwNumber)
//      val projectLinkRight5 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(1), Discontinuity.Continuous, 0L, 15L, 10L, 15L, None, None, None, 12353L, 0.0, 15.2, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferRight5, projectId, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight5), roadwayId + 4, linearLocationId + 4, 8L, reversed = false, None, 86400L, roadwayNumber = nextRwNumber)
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
//      val projectLinkLeft1 = ProjectLink(Sequences.nextProjectLinkId, RoadPart(9999, 1), Track.apply(2), Discontinuity.Continuous,   0L,  5L, 0L,  5L, None, None, None, 12345L, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferLeft1, projectId, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferLeft1), roadwayId,     linearLocationId,     8L, reversed = false, None, 86400L, roadwayNumber = Sequences.nextRoadwayNumber)
//      val projectLinkLeft2 = ProjectLink(Sequences.nextProjectLinkId, RoadPart(9999, 1), Track.apply(2), Discontinuity.ParallelLink, 5L, 10L, 5L, 10L, None, None, None, 12346L, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferLeft2, projectId, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferLeft2), roadwayId + 1, linearLocationId + 1, 8L, reversed = false, None, 86400L, roadwayNumber = Sequences.nextRoadwayNumber)
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
//      val projectLinkRight1 = ProjectLink(Sequences.nextProjectLinkId, RoadPart(9999, 1), Track.apply(1), Discontinuity.Continuous,         0L,  5L, 0L, 0L, None, None, None, 12347L, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNewRight1, projectId, RoadAddressChangeType.New,    AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewRight1), 0, 0, 8L, reversed = false, None, 86400L)
//      val projectLinkRight2 = ProjectLink(Sequences.nextProjectLinkId, RoadPart(9999, 1), Track.apply(1), Discontinuity.MinorDiscontinuity, 5L, 10L, 0L, 0L, None, None, None, 12348L, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNewRight2, projectId, RoadAddressChangeType.New,    AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewRight2), 0, 0, 8L, reversed = false, None, 86400L)
//
//      //  Left: After Roundabout (New)
//      val projectLinkLeft3  = ProjectLink(Sequences.nextProjectLinkId, RoadPart(9999, 1), Track.apply(2), Discontinuity.Continuous, 10L, 15L, 0L, 0L, None, None, None, 12349L, 0.0, 5.1, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNewLeft3,       projectId, RoadAddressChangeType.New,      AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewLeft3), 0, 0, 8L, reversed = false, None, 86400L)
//      //  Right: After roundabout (Transfer)
//      val projectLinkRight3 = ProjectLink(Sequences.nextProjectLinkId, RoadPart(9999, 1), Track.apply(1), Discontinuity.Continuous, 10L, 15L, 0L, 5L, None, None, None, 12352L, 0.0, 5.1, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferRight3, projectId, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight3), roadwayId + 2, linearLocationId + 2, 8L, reversed = false, None, 86400L, roadwayNumber = Sequences.nextRoadwayNumber)
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
      val projectLinkCombined1 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(0), Discontinuity.Continuous,     0L,  5L,  0L,  5L, None, None, None, 12345L, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferCombined1, projectId, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferCombined1), roadwayId1, linearLocationId1, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber1)
      val projectLinkCombined2 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(0), Discontinuity.Continuous,     5L, 10L,  5L, 10L, None, None, None, 12346L, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferCombined2, projectId, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferCombined2), roadwayId2, linearLocationId2, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber2)

      //Left New
      val projectLinkLeft1     = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(2), Discontinuity.Continuous,     0L,  0L,  0L,  0L, None, None, None, 12348L, 0.0, 3.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNewLeft1,          projectId, RoadAddressChangeType.New,      AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewLeft1),          -1000,      -1000,             8L, reversed = false, None, 86400L)
      val projectLinkLeft2     = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(2), Discontinuity.Discontinuous,  0L,  0L,  0L,  0L, None, None, None, 12349L, 0.0, 6.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNewLeft2,          projectId, RoadAddressChangeType.New,      AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewLeft2),          -1000,      -1000,             8L, reversed = false, None, 86400L)

      //Right Transfer
      val projectLinkRight1    = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(1), Discontinuity.Continuous,    10L, 23L, 10L, 23L, None, None, None, 12346L, 5.0, 18.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferRight1,   projectId, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight1),    roadwayId2, linearLocationId3, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber2)
      val projectLinkRight2    = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(1), Discontinuity.Discontinuous, 23L, 30L, 23L, 30L, None, None, None, 12347L, 0.0,  7.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferRight2,   projectId, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight2),    roadwayId3, linearLocationId4, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber3)

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
      val projectLinkCombined1 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(0), Discontinuity.Continuous,  0L,  5L,  0L,  5L, None, None, None, 12345L, 0.0,  5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferCombined1, projectId, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferCombined1), roadwayId1, linearLocationId1, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber1)
      val projectLinkCombined2 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(0), Discontinuity.Continuous,  5L, 10L,  5L, 10L, None, None, None, 12346L, 0.0,  5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferCombined2, projectId, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferCombined2), roadwayId2, linearLocationId2, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber2)

      //Left New
      val projectLinkLeft1 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(2), Discontinuity.Continuous,      0L,  0L,  0L,  0L, None, None, None, 12348L, 0.0,  3.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNewLeft1,          projectId, RoadAddressChangeType.New,      AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewLeft1),          -1000,      -1000,             8L, reversed = false, None, 86400L)
      val projectLinkLeft2 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(2), Discontinuity.Continuous,      0L,  0L,  0L,  0L, None, None, None, 12349L, 0.0,  6.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNewLeft2,          projectId, RoadAddressChangeType.New,      AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewLeft2),          -1000,      -1000,             8L, reversed = false, None, 86400L)
      val projectLinkLeft3 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(2), Discontinuity.Discontinuous,   0L,  0L,  0L,  0L, None, None, None, 12350L, 0.0, 10.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNewLeft3,          projectId, RoadAddressChangeType.New,      AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewLeft3),          -1000,      -1000,             8L, reversed = false, None, 86400L)

      //Right Transfer
      val projectLinkRight1 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(1), Discontinuity.Continuous,    10L, 23L, 10L, 23L, None, None, None, 12346L, 5.0, 18.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferRight1,    projectId, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight1),    roadwayId2, linearLocationId3, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber2)
      val projectLinkRight2 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(1), Discontinuity.Discontinuous, 23L, 30L, 23L, 30L, None, None, None, 12347L, 0.0,  7.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferRight2,    projectId, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight2),    roadwayId3, linearLocationId4, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber3)

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
      val projectLinkCombined1 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(0), Discontinuity.Continuous,     0L,  5L,  0L,  5L, None, None, None, 12345L, 0.0,  5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferCombined1, projectId, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferCombined1), roadwayId1, linearLocationId1, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber1)
      val projectLinkCombined2 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(0), Discontinuity.Continuous,     5L, 10L,  5L, 10L, None, None, None, 12346L, 0.0,  5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferCombined2, projectId, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferCombined2), roadwayId2, linearLocationId2, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber2)

      //  Left New
      val projectLinkLeft1     = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(2), Discontinuity.Discontinuous,  0L,  0L,  0L,  0L, None, None, None, 12348L, 0.0, 11.18, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNewLeft1,         projectId, RoadAddressChangeType.New,      AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewLeft1),          -1000,      -1000,             8L, reversed = false, None, 86400L)

      //  Right Transfer
      val projectLinkRight1    = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(1), Discontinuity.Continuous,    10L, 23L, 10L, 23L, None, None, None, 12346L, 5.0, 18.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferRight1,    projectId, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight1),    roadwayId2, linearLocationId3, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber2)
      val projectLinkRight2    = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(1), Discontinuity.Discontinuous, 23L, 30L, 23L, 30L, None, None, None, 12347L, 0.0,  5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferRight2,    projectId, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight2),    roadwayId3, linearLocationId4, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber3)

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
      val projectLinkCombined1 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(0), Discontinuity.Continuous,  0L,  5L,  0L,  5L, None, None, None, 12345L, 0.0,  5.0,  SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferCombined1, projectId, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferCombined1), roadwayId1, linearLocationId1, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber1)
      val projectLinkCombined2 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(0), Discontinuity.Continuous,  5L, 10L,  5L, 10L, None, None, None, 12346L, 0.0,  5.0,  SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferCombined2, projectId, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferCombined2), roadwayId2, linearLocationId2, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber2)


      //  Left New
      val projectLinkLeft1 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(2), Discontinuity.Continuous,      0L,  0L,  0L,  0L, None, None, None, 12350L, 0.0,  1.0,  SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNewLeft1,          projectId, RoadAddressChangeType.New,      AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewLeft1), -1000, -1000, 8L, reversed = false, None, 86400L)
      val projectLinkLeft2 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(2), Discontinuity.Continuous,      0L,  0L,  0L,  0L, None, None, None, 12348L, 0.0, 10.18, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNewLeft2,          projectId, RoadAddressChangeType.New,      AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewLeft2), -1000, -1000, 8L, reversed = false, None, 86400L)

      //  Right Transfer
      val projectLinkRight1 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(1), Discontinuity.Continuous,    10L, 23L, 10L, 23L, None, None, None, 12346L, 5.0, 18.0,  SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferRight1,    projectId, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight1), roadwayId2, linearLocationId3, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber2)
      val projectLinkRight2 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(1), Discontinuity.Continuous,    23L, 24L, 23L, 24L, None, None, None, 12349L, 0.0,  1.0,  SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferRight2,    projectId, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight2), roadwayId3, linearLocationId4, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber3)
      val projectLinkRight3 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(1), Discontinuity.Discontinuous, 24L, 30L, 23L, 30L, None, None, None, 12347L, 0.0,  5.0,  SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferRight3,    projectId, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight3), roadwayId3, linearLocationId5, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber4)

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
      val projectLinkCombined1 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(0), Discontinuity.Continuous, 0L,  5L,  0L,  5L, None, None, None, 12345L, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferCombined1, projectId, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferCombined1), roadwayId1, linearLocationId1, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber1)

      val projectLinkCombined2 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(0), Discontinuity.EndOfRoad, 20L, 25L, 20L, 25L, None, None, None, 12349L, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferCombined2, projectId, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferCombined2), roadwayId2, linearLocationId6, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber2)

      //  Left New
      val projectLinkLeft1  = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(2), Discontinuity.Continuous,  0L,  0L,  0L,  0L, None, None, None, 12350L, 0.0, 20.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNewLeft1,      projectId, RoadAddressChangeType.New,      AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewLeft1), -1000, -1000, 8L, reversed = false, None, 86400L)
      val projectLinkLeft2  = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(2), Discontinuity.Continuous,  0L,  0L,  0L,  0L, None, None, None, 12351L, 0.0, 20.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNewLeft2,      projectId, RoadAddressChangeType.New,      AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewLeft2), -1000, -1000, 8L, reversed = false, None, 86400L)
      val projectLinkLeft3  = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(2), Discontinuity.Continuous,  0L,  0L,  0L,  0L, None, None, None, 12352L, 0.0, 20.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNewLeft3,      projectId, RoadAddressChangeType.New,      AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewLeft3), -1000, -1000, 8L, reversed = false, None, 86400L)

      //  Right Transfer
      val projectLinkRight1 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(1), Discontinuity.Continuous,  5L, 10L,  5L, 10L, None, None, None, 12346L, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferRight1, projectId, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight1), roadwayId1, linearLocationId2, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber1)
      val projectLinkRight2 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(1), Discontinuity.Continuous, 10L, 13L, 10L, 13L, None, None, None, 12347L, 3.0, 6.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferRight2, projectId, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight2), roadwayId1, linearLocationId3, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber1)
      val projectLinkRight3 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(1), Discontinuity.Continuous, 13L, 16L, 13L, 16L, None, None, None, 12347L, 0.0, 3.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferRight3, projectId, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight3), roadwayId2, linearLocationId4, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber2)
      val projectLinkRight4 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(1), Discontinuity.Continuous, 16L, 20L, 16L, 20L, None, None, None, 12348L, 0.0, 4.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferRight4, projectId, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight4), roadwayId2, linearLocationId5, 8L, reversed = false, None, 86400L, roadwayNumber = roadwayNumber2)

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
      val projectLinkOtherPartComb1 = ProjectLink(plId + 1, RoadPart(9999, 2), Track.Combined,  Discontinuity.Continuous,          0L,  5L,  0L,  5L, None, None, None, 12344L.toString, 0.0, 5.0, SideCode.AgainstDigitizing, (NoCP, NoCP), (NoCP, NoCP), otherPartGeomTransferComb1, 0L, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(otherPartGeomTransferComb1), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkComb1          = ProjectLink(plId + 1, RoadPart(9999, 1), Track.Combined,  Discontinuity.Continuous,          5L, 10L,  5L, 10L, None, None, None, 12345L.toString, 0.0, 5.0, SideCode.AgainstDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferComb1,          0L, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferComb1),          0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkComb2          = ProjectLink(plId + 2, RoadPart(9999, 1), Track.Combined,  Discontinuity.MinorDiscontinuity, 10L, 15L, 10L, 15L, None, None, None, 12346L.toString, 0.0, 5.0, SideCode.AgainstDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferComb2,          0L, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferComb2),          0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkCombNewBefore  = ProjectLink(plId + 3, RoadPart(9999, 1), Track.Combined,  Discontinuity.MinorDiscontinuity,  0L,  5L,  0L,  5L, None, None, None, 12347L.toString, 0.0, 5.0, SideCode.AgainstDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNewComb1,               0L, RoadAddressChangeType.New,      AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewComb1),               0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkNewLeft        = ProjectLink(plId + 4, RoadPart(9999, 1), Track.LeftSide,  Discontinuity.Continuous,          0L,  0L,  0L,  0L, None, None, None, 12348L.toString, 0.0, 0.0, SideCode.Unknown,           (NoCP, NoCP), (NoCP, NoCP), geomLeft1,                  0L, RoadAddressChangeType.New,      AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft1),                  0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkNewRight       = ProjectLink(plId + 5, RoadPart(9999, 1), Track.RightSide, Discontinuity.Continuous,          0L,  0L,  0L,  0L, None, None, None, 12349L.toString, 0.0, 0.0, SideCode.Unknown,           (NoCP, NoCP), (NoCP, NoCP), geomRight1,                 0L, RoadAddressChangeType.New,      AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight1),                 0L, 0, 0, reversed = false, None, 86400L)


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
      val projectLinkNewComb1Before = ProjectLink(plId,     RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, 0L, 0,   0,   0,  None, None, None, 12344L.toString, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNewComb1,      0L, RoadAddressChangeType.New,        AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNewComb1),      0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkComb1          = ProjectLink(plId + 1, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, 0L, 5L,  0L,  5L, None, None, None, 12345L.toString, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferComb1, 0L, RoadAddressChangeType.NotHandled, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferComb1), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkComb2          = ProjectLink(plId + 2, RoadPart(9999, 1), Track.Combined, Discontinuity.EndOfRoad,  5L, 10L, 5L, 10L, None, None, None, 12346L.toString, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferComb2, 0L, RoadAddressChangeType.NotHandled, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferComb2), 0L, 0, 0, reversed = false, None, 86400L)

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
      val projectLinkNotHandledComb1Part1      = ProjectLink(plId,     RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, 0L,  5L, 0L,  5L, None, None, None, 12344L.toString, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNotHandledComb1Part1,      0L, RoadAddressChangeType.NotHandled, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNotHandledComb1Part1),      0L, 0L, 0L, reversed = false, None, 86400L)
      val projectLinkNotHandledComb2Part1      = ProjectLink(plId + 1, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, 5L, 10L, 5L, 10L, None, None, None, 12345L.toString, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNotHandledComb2Part1,      0L, RoadAddressChangeType.NotHandled, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNotHandledComb2Part1),      0L, 0,  0,  reversed = false, None, 86400L)
      val projectLinkTransferComb1Part2ToPart1 = ProjectLink(plId + 2, RoadPart(9999, 1), Track.Combined, Discontinuity.EndOfRoad,  0L,  6L, 0L,  6L, None, None, None, 12346L.toString, 0.0, 6.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferComb1Part2ToPart1, 0L, RoadAddressChangeType.Transfer,   AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferComb1Part2ToPart1), 0L, 0,  0,  reversed = false, None, 86400L)

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
      val projectLinkNotHandledComb1Part1      = ProjectLink(plId,     RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, 0L,  5L, 0L,  5L, None, None, None, 12344L.toString, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNotHandledComb1Part1,      0L, RoadAddressChangeType.NotHandled, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNotHandledComb1Part1),      0L, 0L, 0L, reversed = false, None, 86400L)
      val projectLinkNotHandledComb2Part1      = ProjectLink(plId + 1, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, 5L, 10L, 5L, 10L, None, None, None, 12345L.toString, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNotHandledComb2Part1,      0L, RoadAddressChangeType.NotHandled, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNotHandledComb2Part1),      0L, 0,  0,  reversed = false, None, 86400L)
      val projectLinkTransferComb1Part2ToPart1 = ProjectLink(plId + 2, RoadPart(9999, 1), Track.Combined, Discontinuity.EndOfRoad,  0L,  6L, 0L,  6L, None, None, None, 12346L.toString, 0.0, 6.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferComb1Part2ToPart1, 0L, RoadAddressChangeType.Transfer,   AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferComb1Part2ToPart1), 0L, 0,  0,  reversed = false, None, 86400L)

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
      val projectLinkTransferNewFirstLinkPart2 = ProjectLink(plId + 1, RoadPart(9999, 2), Track.Combined, Discontinuity.Continuous, 5L, 10L, 5L, 10L, None, None, None, 12345L.toString, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferNewFirstLinkPart2, 0L, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferNewFirstLinkPart2), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkTransferOldFirstLinkPart2 = ProjectLink(plId + 2, RoadPart(9999, 2), Track.Combined, Discontinuity.EndOfRoad,  0L,  5L, 0L,  6L, None, None, None, 12346L.toString, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomTransferOldFirstLinkPart2, 0L, RoadAddressChangeType.Transfer, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferOldFirstLinkPart2), 0L, 0, 0, reversed = false, None, 86400L)

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
      val geomNew1 = Seq(Point(0.0,  0.0), Point(0.0, 10.0))
      val geomNew2 = Seq(Point(0.0, 10.0), Point(0.0, 20.0))

      val plId = Sequences.nextProjectLinkId
      val projectLinkNew1 = ProjectLink(plId,     RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, 0L, 10L, 0L, 0L, None, None, None, 12344L.toString, 0.0, 10.0, SideCode.AgainstDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNew1, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew1), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkNew2 = ProjectLink(plId + 1, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, 0L,  0L, 0L, 0L, None, None, None, 12347L.toString, 0.0,  0.0, SideCode.Unknown,           (NoCP, NoCP), (NoCP, NoCP), geomNew2, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew2), 0L, 0, 0, reversed = false, None, 86400L)

      val otherProjectLinks = Seq(projectLinkNew1)
      val newProjectLinks   = Seq(projectLinkNew2)

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
      val geomNew2 = Seq(Point(0.0,  0.0), Point(0.0, 10.0))

      val plId = Sequences.nextProjectLinkId
      val projectLinkNew1 = ProjectLink(plId,     RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, 0L, 10L, 0L, 0L, None, None, None, 12344L.toString, 0.0, 10.0, SideCode.AgainstDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNew1, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew1), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkNew2 = ProjectLink(plId + 1, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, 0L,  0L, 0L, 0L, None, None, None, 12347L.toString, 0.0,  0.0, SideCode.Unknown,           (NoCP, NoCP), (NoCP, NoCP), geomNew2, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew2), 0L, 0, 0, reversed = false, None, 86400L)

      val otherProjectLinks = Seq(projectLinkNew1)
      val newProjectLinks   = Seq(projectLinkNew2)

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
      val geomNew1 = Seq(Point(723.562,44.87,94.7409999999945), Point(792.515,54.912,95.8469999999943))
      val geomNew2 = Seq(Point(973.346,33.188,93.2029999999941), Point(847.231,62.266,94.823000000004), Point(792.515,54.912,95.8469999999943))

      val plId = Sequences.nextProjectLinkId
      val projectLinkNew1 = ProjectLink(plId,     RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12344L.toString, 0.0,  9.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomNew1, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew1), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkNew2 = ProjectLink(plId + 1, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12345L.toString, 0.0, 10.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomNew2, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew2), 0L, 0, 0, reversed = false, None, 86400L)

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

      val geomNew1 = Seq(Point( 5.0, 10.0), Point(5.0, 20.0))
      val geomNew2 = Seq(Point( 0.0,  0.0), Point(5.0, 10.0))
      val geomNew3 = Seq(Point(10.0,  0.0), Point(5.0, 10.0))

      val plId = Sequences.nextProjectLinkId
      val projectLinkNew1 = ProjectLink(plId,     RoadPart(9999, 1), Track.Combined,  Discontinuity.Continuous, 0L, 10L, 0L, 0L, None, None, None, 12344L.toString, 0.0, 10.0, SideCode.AgainstDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNew1, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew1), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkNew2 = ProjectLink(plId + 1, RoadPart(9999, 1), Track.RightSide, Discontinuity.Continuous, 0L,  0L, 0L, 0L, None, None, None, 12345L.toString, 0.0,  0.0, SideCode.Unknown,           (NoCP, NoCP), (NoCP, NoCP), geomNew2, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew2), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkNew3 = ProjectLink(plId + 2, RoadPart(9999, 1), Track.LeftSide,  Discontinuity.Continuous, 0L,  0L, 0L, 0L, None, None, None, 12346L.toString, 0.0,  0.0, SideCode.Unknown,           (NoCP, NoCP), (NoCP, NoCP), geomNew3, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew3), 0L, 0, 0, reversed = false, None, 86400L)

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
      val geomNew2 = Seq(Point(0.0, 0.0), Point(5.0, 10.0))
      val geomNew3 = Seq(Point(10.0, 0.0), Point(5.0, 10.0))

      val plId = Sequences.nextProjectLinkId
      val projectLinkNew1 = ProjectLink(plId,     RoadPart(9999, 1), Track.Combined,  Discontinuity.Continuous, 0L, 10L, 0L, 0L, None, None, None, 12344L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomNew1, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew1), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkNew2 = ProjectLink(plId + 1, RoadPart(9999, 1), Track.LeftSide,  Discontinuity.Continuous, 0L,  0L, 0L, 0L, None, None, None, 12345L.toString, 0.0,  0.0, SideCode.Unknown,           (NoCP, NoCP), (NoCP, NoCP), geomNew2, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew2), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkNew3 = ProjectLink(plId + 2, RoadPart(9999, 1), Track.RightSide, Discontinuity.Continuous, 0L,  0L, 0L, 0L, None, None, None, 12346L.toString, 0.0,  0.0, SideCode.Unknown,           (NoCP, NoCP), (NoCP, NoCP), geomNew3, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew3), 0L, 0, 0, reversed = false, None, 86400L)

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
  private def testNewExistingNew(statusOfExisting: RoadAddressChangeType, sideCode: SideCode): Unit = {
    runWithRollback {
      val geom1    = Seq(Point(0.0, 10.0), Point(0.0, 20.0))
      val geomNew2 = Seq(Point(0.0, 20.0), Point(0.0, 30.0))
      val geomNew3 = Seq(Point(0.0, 0.0), Point(0.0, 10.0))

      val projectLink1    = ProjectLink(1, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, 0L, 10L, 0L, 0L, None, None, None, 12344L.toString, 0.0, 10.0, sideCode,         (NoCP, NoCP), (NoCP, NoCP), geom1,    0L, statusOfExisting,          AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom1),    0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkNew2 = ProjectLink(2, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, 0L,  0L, 0L, 0L, None, None, None, 12345L.toString, 0.0,  0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomNew2, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew2), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkNew3 = ProjectLink(3, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, 0L,  0L, 0L, 0L, None, None, None, 12346L.toString, 0.0,  0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomNew3, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew3), 0L, 0, 0, reversed = false, None, 86400L)

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
    testNewExistingNew(RoadAddressChangeType.NotHandled, SideCode.TowardsDigitizing)
  }

  test("Test findStartingPoints When adding (New) links before and after the existing (New) road that goes towards the digitization Then the road should still maintain the previous existing direction") {
    testNewExistingNew(RoadAddressChangeType.New, SideCode.TowardsDigitizing)
  }

  test("Test findStartingPoints When adding (New) links before and after the existing (Transfer) road that goes towards the digitization Then the road should still maintain the previous existing direction") {
    testNewExistingNew(RoadAddressChangeType.Transfer, SideCode.TowardsDigitizing)
  }

  test("Test findStartingPoints When adding (New) links before and after the existing (NotHandled) road that goes against the digitization Then the road should still maintain the previous existing direction") {
    testNewExistingNew(RoadAddressChangeType.NotHandled, SideCode.AgainstDigitizing)
  }

  test("Test findStartingPoints When adding (New) links before and after the existing (New) road that goes against the digitization Then the road should still maintain the previous existing direction") {
    testNewExistingNew(RoadAddressChangeType.New, SideCode.AgainstDigitizing)
  }

  test("Test findStartingPoints When adding (New) links before and after the existing (Transfer) road that goes against the digitization Then the road should still maintain the previous existing direction") {
    testNewExistingNew(RoadAddressChangeType.Transfer, SideCode.AgainstDigitizing)
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
  private def testNewExistingTwoTrackNew(statusOfExisting: RoadAddressChangeType, sideCode: SideCode): Unit = {
    runWithRollback {
      val geom0 = Seq(Point(10.0, 10.0), Point(5.0, 15.0), Point(10.0, 20.0))
      val geom1 = Seq(Point(10.0, 10.0), Point(15.0, 15.0), Point(10.0, 20.0))

      val projectLink0 = ProjectLink(0, RoadPart(9999, 1), if (sideCode == SideCode.TowardsDigitizing) Track.LeftSide  else Track.RightSide, Discontinuity.Continuous, 0L, 10L, 0L, 0L, None, None, None, 12344L.toString, 0.0, GeometryUtils.geometryLength(geom0), sideCode, (NoCP, NoCP), (NoCP, NoCP), geom0, 0L, statusOfExisting, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom0), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLink1 = ProjectLink(1, RoadPart(9999, 1), if (sideCode == SideCode.TowardsDigitizing) Track.RightSide else Track.LeftSide,  Discontinuity.Continuous, 0L, 10L, 0L, 0L, None, None, None, 12344L.toString, 0.0, GeometryUtils.geometryLength(geom1), sideCode, (NoCP, NoCP), (NoCP, NoCP), geom1, 0L, statusOfExisting, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom1), 0L, 0, 0, reversed = false, None, 86400L)

      val geomNew2 = Seq(Point(10.0, 20.0), Point(10.0, 30.0))
      val geomNew3 = Seq(Point(10.0, 0.0), Point(10.0, 10.0))

      val projectLinkNew2 = ProjectLink(2, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12345L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomNew2, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew2), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLinkNew3 = ProjectLink(3, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12346L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomNew3, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomNew3), 0L, 0, 0, reversed = false, None, 86400L)

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
    testNewExistingTwoTrackNew(RoadAddressChangeType.NotHandled, SideCode.TowardsDigitizing)
  }

  test("Test findStartingPoints When adding (New) links before and after the existing (New) two track road that goes towards the digitization Then the road should still maintain the previous existing direction") {
    testNewExistingTwoTrackNew(RoadAddressChangeType.New, SideCode.TowardsDigitizing)
  }

  test("Test findStartingPoints When adding (New) links before and after the existing (Transfer) two track road that goes towards the digitization Then the road should still maintain the previous existing direction") {
    testNewExistingTwoTrackNew(RoadAddressChangeType.Transfer, SideCode.TowardsDigitizing)
  }

  test("Test findStartingPoints When adding (New) links before and after the existing (NotHandled) two track road that goes against the digitization Then the road should still maintain the previous existing direction") {
    testNewExistingTwoTrackNew(RoadAddressChangeType.NotHandled, SideCode.AgainstDigitizing)
  }

  test("Test findStartingPoints When adding (New) links before and after the existing (New) two track road that goes against the digitization Then the road should still maintain the previous existing direction") {
    testNewExistingTwoTrackNew(RoadAddressChangeType.New, SideCode.AgainstDigitizing)
  }

  test("Test findStartingPoints When adding (New) links before and after the existing (Transfer) two track road that goes against the digitization Then the road should still maintain the previous existing direction") {
    testNewExistingTwoTrackNew(RoadAddressChangeType.Transfer, SideCode.AgainstDigitizing)
  }

}

package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.asset.{LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.{NewIdValue, RoadType}
import fi.liikennevirasto.viite.RoadType.UnknownOwnerRoad
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.CalibrationPointType.NoCP
import fi.liikennevirasto.viite.process._
import org.joda.time.DateTime
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation

class RoadwayChangesDAOSpec extends FunSuite with Matchers {

  def runWithRollback(f: => Unit): Unit = {
    Database.forDataSource(OracleDatabase.ds).withDynTransaction {
      f
      dynamicSession.rollback()
    }
  }

  val projectDAO = new ProjectDAO
  private val roadNumber1 = 990
  private val roadwayNumber1 = 1000000000l
  private val roadPartNumber1 = 1
  private def dummyProject(id: Long, status: ProjectState, reservedParts: Seq[ProjectReservedPart] = List.empty[ProjectReservedPart], coordinates: Option[ProjectCoordinates] = None): Project ={
    Project(id, status, "testProject", "testUser", DateTime.parse("2001-01-01"), "testUser", DateTime.parse("2001-01-01"), DateTime.now(), "additional info here", reservedParts, Seq(), Some("current status info"), coordinates)
  }

  def addprojects(): Unit = {
    sqlu"""insert into project (id,state,name,created_by, start_date) VALUES (1,0,'testproject','automatedtest', current_date)""".execute
    sqlu"""insert into project (id,state,name,created_by, start_date) VALUES (2,0,'testproject2','automatedtest', current_date)""".execute
    sqlu"""INSERT INTO PROJECT_RESERVED_ROAD_PART VALUES (1, 1, 1, 1, '-')""".execute
    sqlu"""
      INSERT INTO PROJECT_LINK (ID, PROJECT_ID, TRACK, DISCONTINUITY_TYPE, ROAD_NUMBER, ROAD_PART_NUMBER,
        START_ADDR_M, END_ADDR_M, CREATED_BY, MODIFIED_BY, CREATED_DATE, MODIFIED_DATE, STATUS,
        ROAD_TYPE, ROADWAY_ID, LINEAR_LOCATION_ID, CONNECTED_LINK_ID, ELY, REVERSED, SIDE, START_MEASURE, END_MEASURE,
        LINK_ID, ADJUSTED_TIMESTAMP, LINK_SOURCE, GEOMETRY, ORIGINAL_START_ADDR_M, ORIGINAL_END_ADDR_M, ROADWAY_NUMBER,
        START_CALIBRATION_POINT, END_CALIBRATION_POINT, ORIG_START_CALIBRATION_POINT, ORIG_END_CALIBRATION_POINT)
      VALUES (1, 1, 0, 5, 1, 1,
        0, 86, 'test user', 'test user', TIMESTAMP '2018-03-23 12:26:36.000000', TIMESTAMP '2018-03-23 12:26:36.000000', 2,
        1, NULL, NULL, NULL, 8, 0, 2, 0, 85.617,
        5170979, 1500079296000, 1, '', 0, 86, NULL,
        3, 3, 3, 3
      )""".execute

  }

  test("Test RoadwayChangesDAO().fetchRoadwayChanges() When searching for changes on a project with roadway changes Then return said changes."){
    runWithRollback{
      //inserts one case
      val addresses = List(ProjectReservedPart(5:Long, 203:Long, 203:Long, Some(6L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
      val project = Project(100,ProjectState.Incomplete,"testiprojekti","Test",DateTime.now(),"Test",DateTime.now(),DateTime.now(),"info",addresses,Seq(),None)
      projectDAO.create(project)
      sqlu""" insert into ROADWAY_CHANGES(project_id,change_type,new_road_number,new_road_part_number,new_TRACK,new_start_addr_m,new_end_addr_m,new_discontinuity,new_road_type,new_ely, ROADWAY_CHANGE_ID) Values(100,1,6,1,1,0,10.5,1,1,8, 1) """.execute
      val projectId = sql"""Select p.id From Project p Inner Join ROADWAY_CHANGES rac on p.id = rac.project_id""".as[Long].first
      val changesList = new RoadwayChangesDAO().fetchRoadwayChanges(Set(projectId))
      changesList.isEmpty should be(false)
      changesList.head.projectId should be(projectId)
    }
  }

  test("Test RoadwayChangesDAO().insertDeltaToRoadChangeTable() When inserting the results of the delta calculation for a project Then when querying directly the roadway_changes it should confirm data insertion.") {
    val newProjectLink = ProjectLink(1, 1, 1, Track.Unknown, Discontinuity.Continuous, 0, 0, 0, 0, None, None, None, 0, 0.0, 0.0,
      SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), List(), 1, LinkStatus.New, UnknownOwnerRoad, LinkGeomSource.NormalLinkInterface, 0.0, 0, 0, 5, reversed = false,
      None, 748800L)
    val delta = Delta(DateTime.now(), Seq(newProjectLink), Termination(Seq()), Unchanged(Seq()), Transferred(Seq()), ReNumeration(Seq()))
    runWithRollback {
      addprojects()
      val project1 = projectDAO.fetchById(1).get
      val reservedParts = Seq(ProjectReservedPart(0, 1, 1, Some(0), Some(Discontinuity.Continuous), Some(8L), None, None, None, Some(12345L)))
      new RoadwayChangesDAO().insertDeltaToRoadChangeTable(delta, 1, Some(project1.copy(reservedParts = reservedParts)))
      sql"""Select Project_Id From ROADWAY_CHANGES Where Project_Id In (1)""".as[Long].firstOption.getOrElse(0) should be(1)
    }
  }

  test("Test RoadwayChangesDAO().insertDeltaToRoadChangeTable() When inserting the results of the delta calculation for a project, the inserted ely code should be the roadway ely instead of project ely") {
    val newProjectLink = ProjectLink(1, 1, 1, Track.Unknown, Discontinuity.Continuous, 0, 0, 0, 0, None, None, None, 0, 0.0, 0.0,
      SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP),  List(), 1, LinkStatus.New, UnknownOwnerRoad, LinkGeomSource.NormalLinkInterface, 0.0, 0, 0, 5, reversed = false,
      None, 748800L)
    val delta = Delta(DateTime.now(), Seq(newProjectLink), Termination(Seq()), Unchanged(Seq()), Transferred(Seq()), ReNumeration(Seq()))
    runWithRollback {
      addprojects()
      val project1 = projectDAO.fetchById(1).get
      val reservedParts = Seq(ProjectReservedPart(0, 1, 1, Some(0), Some(Discontinuity.Continuous), Some(8L), None, None, None, Some(12345L)))
      val dao = new RoadwayChangesDAO()
      dao.insertDeltaToRoadChangeTable(delta, 1, Some(project1.copy(reservedParts = reservedParts)))
      val changes = dao.fetchRoadwayChanges(Set(1))
      changes.foreach(c => {
        c.changeInfo.target.ely.get should be(5)
      })
    }
  }

  test("Test query for Roadway_change changes api") {
    runWithRollback {
      val testRoadway1 = Roadway(NewIdValue, roadwayNumber1, roadNumber1, roadPartNumber1, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
        0, 100, reversed = false, DateTime.parse("2000-01-02"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)
      val dao = new RoadwayChangesDAO()
      val roadwayDAO = new RoadwayDAO

      val rw = roadwayDAO.create(Seq(testRoadway1))
      val rwId = rw(0)

      sqlu""" update ROADWAY R SET VALID_FROM = TIMESTAMP '2120-01-02 12:26:36.000000' WHERE ID =  $rwId """.execute
      val rws = roadwayDAO.fetchAllByRoadwayId(rw)
      val projId1 = Sequences.nextViiteProjectId
      val rap =  dummyProject(projId1, ProjectState.Incomplete, List(), None)
      val pr = projectDAO.create(rap)
      val pr2 = projectDAO.fetchById(projId1)
      val changeType = 2
      sqlu""" insert into ROADWAY_CHANGES(project_id,change_type,old_road_number,old_road_part_number,old_TRACK,old_start_addr_m,old_end_addr_m,old_discontinuity,new_discontinuity,old_road_type,new_road_type,old_ely,new_ely, ROADWAY_CHANGE_ID,new_road_number,new_road_part_number)
                                   Values($projId1,$changeType,$roadNumber1,$roadPartNumber1,1,0,10.5,1,1,1,1,8,8,1,$roadNumber1,$roadPartNumber1) """.execute
      val rwcs = dao.fetchRoadwayChanges(Set(projId1))
      val startValidFromDate = DateTime.parse("2120-01-01")
      val endValidFromDate =  DateTime.parse("2120-01-03")
      var roadwayChangesInfo = dao.fetchRoadwayChangesInfo(startValidFromDate, Option(endValidFromDate))

      roadwayChangesInfo.size should be(1)
      roadwayChangesInfo(0).change_type should be(changeType)

      sqlu""" insert into ROADWAY_CHANGES(project_id,change_type,old_road_number,old_road_part_number,old_TRACK,old_start_addr_m,old_end_addr_m,old_discontinuity,new_discontinuity,old_road_type,new_road_type,old_ely,new_ely, ROADWAY_CHANGE_ID)
                                   Values($projId1,3,$roadNumber1,$roadPartNumber1,1,0,10.5,1,1,1,1,8,8,1) """.execute
      roadwayChangesInfo = dao.fetchRoadwayChangesInfo(startValidFromDate, Option(endValidFromDate))
      roadwayChangesInfo.size should be(2)
    }
  }

}

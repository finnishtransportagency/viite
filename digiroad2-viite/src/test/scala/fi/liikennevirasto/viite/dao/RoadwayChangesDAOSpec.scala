package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.asset.{LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.Dummies.dummyRoadwayChangeSection
import fi.liikennevirasto.viite.{ProjectService, RoadType}
import fi.liikennevirasto.viite.RoadType.UnknownOwnerRoad
import fi.liikennevirasto.viite.dao.Discontinuity.Continuous
import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination
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

  def addprojects(): Unit = {
    sqlu"""insert into project (id,state,name,created_by, start_date) VALUES (1,0,'testproject','automatedtest', sysdate)""".execute
    sqlu"""insert into project (id,state,name,created_by, start_date) VALUES (2,0,'testproject2','automatedtest', sysdate)""".execute
    sqlu"""INSERT INTO PROJECT_RESERVED_ROAD_PART VALUES (1, 1, 1, 1, '-')""".execute
    sqlu"""INSERT INTO PROJECT_LINK VALUES (1, 1, 0, 5, 1, 1, 0, 86, 'test user', 'test user', TIMESTAMP '2018-03-23 12:26:36.000000', TIMESTAMP '2018-03-23 12:26:36.000000', 2, 3, 1, NULL, NULL, NULL, 8, 0, 2, 0, 85.617, 5170979, 1500079296000, 1, 0, '', 0, 86)""".execute

  }

  test("Test RoadwayChangesDAO().fetchRoadwayChanges() When searching for changes on a project with roadway changes Then return said changes."){
    runWithRollback{
      //inserts one case
      val addresses = List(ProjectReservedPart(5:Long, 203:Long, 203:Long, Some(6L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
      val project = Project(100,ProjectState.Incomplete,"testiprojekti","Test",DateTime.now(),"Test",DateTime.now(),DateTime.now(),"info",addresses, None)
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
      SideCode.Unknown, (None, None), List(), 1, LinkStatus.New, UnknownOwnerRoad, LinkGeomSource.NormalLinkInterface, 0.0, 0, 0, 5, reversed = false,
      None, 748800L)
    val delta = Delta(DateTime.now(), Seq(newProjectLink), Termination(Seq()), Unchanged(Seq()), Transferred(Seq()), ReNumeration(Seq()))
    runWithRollback {
      addprojects()
      new RoadwayChangesDAO().insertDeltaToRoadChangeTable(delta, 1)
      sql"""Select Project_Id From ROADWAY_CHANGES Where Project_Id In (1)""".as[Long].firstOption.get should be(1)
    }
  }

  test("Test RoadwayChangesDAO().insertDeltaToRoadChangeTable() When inserting the results of the delta calculation for a project, the inserted ely code should be the roadway ely instead of project ely") {
    val newProjectLink = ProjectLink(1, 1, 1, Track.Unknown, Discontinuity.Continuous, 0, 0, 0, 0, None, None, None, 0, 0.0, 0.0,
      SideCode.Unknown, (None, None),  List(), 1, LinkStatus.New, UnknownOwnerRoad, LinkGeomSource.NormalLinkInterface, 0.0, 0, 0, 5, reversed = false,
      None, 748800L)
    val delta = Delta(DateTime.now(), Seq(newProjectLink), Termination(Seq()), Unchanged(Seq()), Transferred(Seq()), ReNumeration(Seq()))
    runWithRollback {
      addprojects()
      val dao = new RoadwayChangesDAO()
      dao.insertDeltaToRoadChangeTable(delta, 1)
      val changes = dao.fetchRoadwayChanges(Set(1))
      changes.foreach(c => {
        c.changeInfo.target.ely.get should be(5)
      })
    }
  }
}

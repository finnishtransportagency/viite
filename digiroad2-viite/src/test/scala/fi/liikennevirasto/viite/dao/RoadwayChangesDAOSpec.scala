package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.asset.AdministrativeClass.{Municipality, State}
import fi.liikennevirasto.digiroad2.asset.{AdministrativeClass, LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.NewIdValue
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.CalibrationPointType.{JunctionPointCP, NoCP, RoadAddressCP}
import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination
import fi.liikennevirasto.viite.util.{projectLinkDAO, projectReservedPartDAO}
import org.joda.time.DateTime
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation

class RoadwayChangesDAOSpec extends FunSuite with Matchers {

  def runWithRollback(f: => Unit): Unit = {
    Database.forDataSource(PostGISDatabase.ds).withDynTransaction {
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
        ADMINISTRATIVE_CLASS, ROADWAY_ID, LINEAR_LOCATION_ID, CONNECTED_LINK_ID, ELY, REVERSED, SIDE, START_MEASURE, END_MEASURE,
        LINK_ID, ADJUSTED_TIMESTAMP, LINK_SOURCE, GEOMETRY, ORIGINAL_START_ADDR_M, ORIGINAL_END_ADDR_M, ROADWAY_NUMBER,
        START_CALIBRATION_POINT, END_CALIBRATION_POINT, ORIG_START_CALIBRATION_POINT, ORIG_END_CALIBRATION_POINT)
      VALUES (1, 1, 0, 5, 1, 1,
        0, 86, 'test user', 'test user', TIMESTAMP '2018-03-23 12:26:36.000000', TIMESTAMP '2018-03-23 12:26:36.000000', 2,
        1, NULL, NULL, NULL, 8, 0, 2, 0, 85.617,
        5170979, 1500079296000, 1, ST_GeomFromText('LINESTRING EMPTY', 3067), 0, 86, NULL,
        3, 3, 3, 3
      )""".execute
  }

  test("Test RoadwayChangesDAO().fetchRoadwayChanges() When searching for changes on a project with roadway changes Then return said changes."){
    runWithRollback{
      //inserts one case
      val addresses = List(ProjectReservedPart(5:Long, 203:Long, 203:Long, Some(6L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
      val project = Project(100,ProjectState.Incomplete,"testiprojekti","Test",DateTime.now(),"Test",DateTime.now(),DateTime.now(),"info",addresses,Seq(),None)
      projectDAO.create(project)
      sqlu""" insert into ROADWAY_CHANGES(project_id,change_type,new_road_number,new_road_part_number,new_TRACK,new_start_addr_m,new_end_addr_m,new_discontinuity,NEW_ADMINISTRATIVE_CLASS,new_ely, ROADWAY_CHANGE_ID) Values(100,1,6,1,1,0,10.5,1,1,8, 1) """.execute
      val projectId = sql"""Select p.id From Project p Inner Join ROADWAY_CHANGES rac on p.id = rac.project_id""".as[Long].first
      val changesList = new RoadwayChangesDAO().fetchRoadwayChanges(Set(projectId))
      changesList.isEmpty should be(false)
      changesList.head.projectId should be(projectId)
    }
  }

  test("Test RoadwayChangesDAO().insertDeltaToRoadChangeTable() When inserting the results of the delta calculation for a project Then when querying directly the roadway_changes it should confirm data insertion.") {
    runWithRollback {
      addprojects()
      val project1 = projectDAO.fetchById(1).get
      val reservedParts = Seq(ProjectReservedPart(0, 1, 1, Some(0), Some(Discontinuity.Continuous), Some(8L), None, None, None, Some(12345L.toString)))
      new RoadwayChangesDAO().insertDeltaToRoadChangeTable(1, Some(project1.copy(reservedParts = reservedParts)))
      sql"""Select Project_Id From ROADWAY_CHANGES Where Project_Id In (1)""".as[Long].firstOption.getOrElse(0) should be(1)
    }
  }

  test("Test RoadwayChangesDAO().insertDeltaToRoadChangeTable() When inserting the results of the delta calculation for a project, the inserted ely code should be the roadway ely instead of project ely") {
    val newProjectLink = ProjectLink(1, 1, 1, Track.Unknown, Discontinuity.Continuous, 0, 0, 0, 0, None, None, None, 0.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), List(), 1, LinkStatus.New, AdministrativeClass.Unknown, LinkGeomSource.NormalLinkInterface, 0.0, 0, 0, 5, reversed = false, None, 748800L)
    runWithRollback {
      addprojects()
      val project1 = projectDAO.fetchById(1).get
      val projectLinks = projectLinkDAO.fetchProjectLinks(1)
      val projectLink1 = projectLinks.head
      val ra = Seq(
        RoadAddress(12345, projectLink1.linearLocationId, projectLink1.roadNumber, projectLink1.roadPartNumber, projectLink1.administrativeClass, projectLink1.track, projectLink1.discontinuity, projectLink1.startAddrMValue, projectLink1.endAddrMValue, projectLink1.startDate, projectLink1.endDate, projectLink1.createdBy, projectLink1.linkId, projectLink1.startMValue, projectLink1.endMValue, projectLink1.sideCode, DateTime.now().getMillis, projectLink1.calibrationPoints, projectLink1.geometry, projectLink1.linkGeomSource, 8, NoTermination, projectLink1.roadwayNumber, None, None, None)
      )
      projectLinkDAO.updateProjectLinks(Seq(newProjectLink), project1.createdBy, ra)
      val reservedParts = Seq(ProjectReservedPart(0, 1, 1, Some(0), Some(Discontinuity.Continuous), Some(8L), None, None, None, Some(12345L.toString)))
      val dao = new RoadwayChangesDAO()
      dao.insertDeltaToRoadChangeTable(1, Some(project1.copy(reservedParts = reservedParts)))
      val changes = dao.fetchRoadwayChanges(Set(1))
      changes.foreach(c => {
        c.changeInfo.target.ely.get should be(5)
      })
    }
  }

  test("Test RoadwayChangesDAO().insertDeltaToRoadChangeTable() When a road is transferred to another road with reverse then roadway changetable should have one road reversed and the others roadnumber changed .") {
    val projId1 = 1
    val targetRoadNumber = 1
    val otherRoadSourceRoadNumber = 2
    val roadPartNumber = 1

    runWithRollback {
      val (rw1, rw2, rw3, rw4, rw5) = (Sequences.nextRoadwayId, Sequences.nextRoadwayId, Sequences.nextRoadwayId, Sequences.nextRoadwayId, Sequences.nextRoadwayId)
      val projectLink1              = ProjectLink(1, targetRoadNumber, roadPartNumber, Track.LeftSide, Discontinuity.Continuous, 15, 20, 0, 5, None, None, Some("test"), 0.toString, 0.0, 0.0, SideCode.Unknown, (JunctionPointCP, RoadAddressCP), (JunctionPointCP, JunctionPointCP), List(), projId1, LinkStatus.Transfer, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, 0.0, rw1, 0, 5, reversed = true, None, 748800L, 1111)
      val projectLink2              = ProjectLink(2, targetRoadNumber, roadPartNumber, Track.RightSide, Discontinuity.Continuous, 15, 20, 0, 5, None, None, Some("test"), 0.toString, 0.0, 0.0, SideCode.Unknown, (JunctionPointCP, JunctionPointCP), (JunctionPointCP, JunctionPointCP), List(), projId1, LinkStatus.Transfer, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, 0.0, rw2, 0, 5, reversed = true, None, 748800L, 1112)
      val projectLink3              = ProjectLink(3, targetRoadNumber, roadPartNumber, Track.LeftSide, Discontinuity.Continuous, 10, 15, 5, 10, None, None, Some("test"), 0.toString, 0.0, 0.0, SideCode.Unknown, (RoadAddressCP, JunctionPointCP), (JunctionPointCP, NoCP), List(), projId1, LinkStatus.Transfer, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, 0.0, rw1, 0, 5, reversed = true, None, 748800L, 1111)
      val projectLink4              = ProjectLink(4, targetRoadNumber, roadPartNumber, Track.RightSide, Discontinuity.Continuous, 10, 15, 5, 10, None, None, Some("test"), 0.toString, 0.0, 0.0, SideCode.Unknown, (RoadAddressCP, JunctionPointCP), (JunctionPointCP, NoCP), List(), projId1, LinkStatus.Transfer, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, 0.0, rw2, 0, 5, reversed = true, None, 748800L, 1112)
      val projectLink5              = ProjectLink(5, targetRoadNumber, roadPartNumber, Track.Combined, Discontinuity.Continuous, 0, 10, 10, 20, None, None, Some("test"), 0.toString, 0.0, 0.0, SideCode.Unknown, (RoadAddressCP, RoadAddressCP), (NoCP, RoadAddressCP), List(), projId1, LinkStatus.Transfer, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, 0.0, rw3, 0, 5, reversed = true, None, 748800L, 1113)

      val projectLink6 = ProjectLink(6, targetRoadNumber, roadPartNumber, Track.LeftSide, Discontinuity.Continuous, 20, 25, 10, 15, None, None, Some("test"), 0.toString, 0.0, 0.0, SideCode.Unknown, (RoadAddressCP, JunctionPointCP), (JunctionPointCP, JunctionPointCP), List(), projId1, LinkStatus.Transfer, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, 0.0, rw4, 0, 5, reversed = false, None, 748800L, 2221)
      val projectLink7 = ProjectLink(7, targetRoadNumber, roadPartNumber, Track.RightSide, Discontinuity.Discontinuous, 20, 60, 10, 50, None, None, Some("test"), 0.toString, 0.0, 0.0, SideCode.Unknown, (JunctionPointCP, RoadAddressCP), (JunctionPointCP, JunctionPointCP), List(), projId1, LinkStatus.Transfer, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, 0.0, rw5, 0, 5, reversed = false, None, 748800L, 2222)
      val projectLink8 = ProjectLink(8, targetRoadNumber, roadPartNumber, Track.LeftSide, Discontinuity.Discontinuous, 25, 60, 15, 50, None, None, Some("test"), 0.toString, 0.0, 0.0, SideCode.Unknown, (JunctionPointCP, RoadAddressCP), (JunctionPointCP, JunctionPointCP), List(), projId1, LinkStatus.Transfer, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, 0.0, rw4, 0, 5, reversed = false, None, 748800L, 2221)

      val project = dummyProject(projId1, ProjectState.Incomplete, List(ProjectReservedPart(1, 1, 1), ProjectReservedPart(1, 2, 1)), None)
      projectDAO.create(project)
      projectReservedPartDAO.reserveRoadPart(projId1, targetRoadNumber, roadPartNumber, "test")
      projectReservedPartDAO.reserveRoadPart(projId1, otherRoadSourceRoadNumber, roadPartNumber, "test")
      val reservedParts = projectReservedPartDAO.fetchReservedRoadParts(projId1)
      val project1      = projectDAO.fetchById(projId1).get
      val rws           = Seq(Roadway(rw1, projectLink1.roadwayNumber, targetRoadNumber, roadPartNumber, AdministrativeClass.Municipality, Track.RightSide, Discontinuity.Continuous, 0, 10, reversed = false, DateTime.parse("2020-01-03"), None, "test", None, 5L, NoTermination),
                              Roadway(rw2, projectLink2.roadwayNumber, targetRoadNumber, roadPartNumber, AdministrativeClass.Municipality, Track.LeftSide, Discontinuity.Continuous, 0, 10, reversed = false, DateTime.parse("2020-01-03"), None, "test", None, 5L, NoTermination),
                              Roadway(rw3, projectLink5.roadwayNumber, targetRoadNumber, roadPartNumber, AdministrativeClass.Municipality, Track.Combined, Discontinuity.Discontinuous, 10, 20, reversed = false, DateTime.parse("2020-01-03"), None, "test", None, 5L, NoTermination),
                              Roadway(rw4, projectLink6.roadwayNumber, otherRoadSourceRoadNumber, roadPartNumber, AdministrativeClass.Municipality, Track.LeftSide, Discontinuity.Discontinuous, 10, 50, reversed = false, DateTime.parse("2020-01-03"), None, "test", None, 5L, NoTermination),
                              Roadway(rw5, projectLink7.roadwayNumber, otherRoadSourceRoadNumber, roadPartNumber, AdministrativeClass.Municipality, Track.RightSide, Discontinuity.Discontinuous, 10, 50, reversed = false, DateTime.parse("2020-01-03"), None, "test", None, 5L, NoTermination)
                             )

      val roadwayDAO = new RoadwayDAO
      roadwayDAO.create(rws)
      projectLinkDAO.create(Seq(projectLink1, projectLink2, projectLink3, projectLink4, projectLink5, projectLink6, projectLink7, projectLink8))

      val roadwayChangesDAO = new RoadwayChangesDAO()
      roadwayChangesDAO.insertDeltaToRoadChangeTable(projId1, Some(project1.copy(reservedParts = reservedParts)))

      val changes = roadwayChangesDAO.fetchRoadwayChanges(Set(projId1))
      changes should have size 5

      val (reversedRoad, notReversedRoad) = changes.partition(_.changeInfo.reversed)
      reversedRoad should have size 3

      reversedRoad.foreach(c => {
        c.changeInfo.source.roadNumber.get should be(targetRoadNumber)
        c.changeInfo.target.roadNumber.get should be(targetRoadNumber)
      })

      notReversedRoad.foreach(c => {
        c.changeInfo.source.roadNumber.get should be(otherRoadSourceRoadNumber)
        c.changeInfo.target.roadNumber.get should be(targetRoadNumber)
      })

      changes.foreach(c => {
        c.changeInfo.source.endAddressM.get should be > c.changeInfo.source.startAddressM.get
        c.changeInfo.target.endAddressM.get should be > c.changeInfo.target.startAddressM.get

        c.changeInfo.source.startRoadPartNumber.get should be(roadPartNumber)
        c.changeInfo.source.administrativeClass.get should be(Municipality)
        c.changeInfo.source.ely.get should be(rws.head.ely)
        c.changeInfo.target.startRoadPartNumber.get should be(roadPartNumber)
        c.changeInfo.target.administrativeClass.get should be(Municipality)
        c.changeInfo.target.ely.get should be(rws.head.ely)
      })
    }
  }

  test("Test RoadwayChangesDAO().insertDeltaToRoadChangeTable() When administrative class changes in the middle of an existing road.") {
    val projId1      = 1
    val projectLink1 = ProjectLink(1, 1, 1, Track.Combined, Discontinuity.Continuous, 0, 10, 0, 10, None, None, Some("test"), 0.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), List(), projId1, LinkStatus.UnChanged, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, 0.0, 0, 0, 5, reversed = false, None, 748800L, 1111)
    val projectLink2 = ProjectLink(2, 1, 1, Track.Combined, Discontinuity.Continuous, 10, 20, 10, 20, None, None, Some("test"), 0.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), List(), projId1, LinkStatus.UnChanged, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, 0.0, 0, 0, 5, reversed = false, None, 748800L, 1111)
    val projectLink3 = ProjectLink(3, 1, 1, Track.Combined, Discontinuity.Continuous, 20, 30, 20, 30, None, None, Some("test"), 0.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), List(), projId1, LinkStatus.UnChanged, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 0.0, 0, 0, 5, reversed = false, None, 748800L, 1111)
    val projectLink4 = ProjectLink(4, 1, 1, Track.Combined, Discontinuity.Discontinuous, 30, 40, 30, 40, None, None, Some("test"), 0.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), List(), projId1, LinkStatus.UnChanged, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 0.0, 0, 0, 5, reversed = false, None, 748800L, 1111)
    runWithRollback {
      val rap = dummyProject(projId1, ProjectState.Incomplete, List(ProjectReservedPart(1, 1, 1)), None)
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(projId1, 1, 1, "test")
      val project1   = projectDAO.fetchById(projId1).get
      val rw         = Seq(Roadway(0, 1111, projectLink4.roadNumber, projectLink4.roadPartNumber, AdministrativeClass.Municipality, Track.Combined, Discontinuity.Discontinuous, 0, 40, reversed = false, DateTime.parse("2020-01-03"), None, "test", None, 5L, NoTermination))
      val roadwayDAO = new RoadwayDAO
      roadwayDAO.create(rw)
      projectLinkDAO.create(Seq(projectLink1, projectLink2, projectLink3, projectLink4))
      val reservedParts     = Seq(ProjectReservedPart(0, 1, 1, Some(0), Some(Discontinuity.Discontinuous), Some(8L), None, None, None, Some(12345L.toString)))
      val roadwayChangesDAO = new RoadwayChangesDAO()
      roadwayChangesDAO.insertDeltaToRoadChangeTable(1, Some(project1.copy(reservedParts = reservedParts)))
      val changes = roadwayChangesDAO.fetchRoadwayChanges(Set(1))
      changes should have size 2
      changes.foreach(c => {
        c.changeInfo.source.roadNumber.get should be(1)
        c.changeInfo.source.startRoadPartNumber.get should be(1)
        c.changeInfo.source.trackCode.get should be(0)
        c.changeInfo.source.ely.get should be(5)
        c.changeInfo.target.roadNumber.get should be(1)
        c.changeInfo.target.startRoadPartNumber.get should be(1)
        c.changeInfo.target.trackCode.get should be(0)
        c.changeInfo.target.ely.get should be(5)
      })
      val (changesToMunicipality, changesToState) = changes.sortBy(_.changeInfo.orderInChangeTable).partition(_.changeInfo.target.startAddressM.get == 0)
      changesToMunicipality.head.changeInfo.source.administrativeClass should be(Some(Municipality))
      changesToMunicipality.head.changeInfo.target.administrativeClass should be(Some(Municipality))
      changesToMunicipality.head.changeInfo.source.discontinuity should be(Some(Discontinuity.Continuous))
      changesToMunicipality.head.changeInfo.target.discontinuity should be(Some(Discontinuity.Continuous))

      changesToState.head.changeInfo.source.administrativeClass should be(Some(Municipality))
      changesToState.head.changeInfo.target.administrativeClass should be(Some(State))
      changesToState.head.changeInfo.source.discontinuity should be(Some(Discontinuity.Discontinuous))
      changesToState.head.changeInfo.target.discontinuity should be(Some(Discontinuity.Discontinuous))
    }
  }

  test("Test query for Roadway_change changes api") {
    runWithRollback {
      val testRoadway1 = Roadway(NewIdValue, roadwayNumber1, roadNumber1, roadPartNumber1, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0, 100, reversed = false, DateTime.parse("2000-01-02"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)
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
      sqlu""" insert into ROADWAY_CHANGES(project_id,change_type,old_road_number,old_road_part_number,old_TRACK,old_start_addr_m,old_end_addr_m,old_discontinuity,new_discontinuity,OLD_ADMINISTRATIVE_CLASS,NEW_ADMINISTRATIVE_CLASS,old_ely,new_ely, ROADWAY_CHANGE_ID,new_road_number,new_road_part_number)
                                   Values($projId1,$changeType,$roadNumber1,$roadPartNumber1,1,0,10.5,1,1,1,1,8,8,1,$roadNumber1,$roadPartNumber1) """.execute
      val rwcs = dao.fetchRoadwayChanges(Set(projId1))
      val startValidFromDate = DateTime.parse("2120-01-01")
      val endValidFromDate =  DateTime.parse("2120-01-03")
      var roadwayChangesInfo = dao.fetchRoadwayChangesInfo(startValidFromDate, Option(endValidFromDate))

      roadwayChangesInfo.size should be(1)
      roadwayChangesInfo(0).change_type should be(changeType)

      sqlu""" insert into ROADWAY_CHANGES(project_id,change_type,old_road_number,old_road_part_number,old_TRACK,old_start_addr_m,old_end_addr_m,old_discontinuity,new_discontinuity,OLD_ADMINISTRATIVE_CLASS,NEW_ADMINISTRATIVE_CLASS,old_ely,new_ely, ROADWAY_CHANGE_ID)
                                   Values($projId1,3,$roadNumber1,$roadPartNumber1,1,0,10.5,1,1,1,1,8,8,1) """.execute
      roadwayChangesInfo = dao.fetchRoadwayChangesInfo(startValidFromDate, Option(endValidFromDate))
      roadwayChangesInfo.size should be(2)
    }
  }

  /*
   * Before project:
   *       roadway2                    roadway1
   *     initialRoadPart            initialRoadPart
   * 0 |----------------> 100 100 |-----------------> 200
   *
   * After project:
   *        roadway2                   roadway1
   *   transferredRoadPart          initialRoadPart
   * 0 |----------------> 100   0 |-----------------> 100
   *
   * Expected two changes:
   * roadway2:
   * initialRoadPart -> transferredRoadPart
   *
   * roadway1:
   * start_addr_m 100 -> start_addr_m 0
   * end_addr_m 200 -> start_addr_m 100
   */
  test("When roadway is partially transferred to another roadaddress" +
    "Then roadway_change changes API returns only one row for each roadway_change_id in roadway_changes") {
    runWithRollback {
      val roadNumber=990
      val initialRoadPart=2
      val transferredRoadPart=1

      val oldroadway1 = Roadway(NewIdValue, roadwayNumber1, roadNumber, initialRoadPart, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 100, 200, reversed = false, DateTime.parse("2000-01-02"), Some(DateTime.parse("2010-01-01")), "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)
      val oldroadway2 = Roadway(NewIdValue, roadwayNumber1, roadNumber, initialRoadPart, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0, 100, reversed = false, DateTime.parse("2000-01-02"), Some(DateTime.parse("2010-01-01")), "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)

      val roadway1 = Roadway(NewIdValue, roadwayNumber1, roadNumber, initialRoadPart, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0, 100, reversed = false, DateTime.parse("2010-01-02"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)
      val roadway2 = Roadway(NewIdValue, roadwayNumber1+1, roadNumber, transferredRoadPart, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0, 100, reversed = false, DateTime.parse("2010-01-02"), None, "test", Some("TEST ROAD 2"), 1, TerminationCode.NoTermination)

      val roadwayChangesDAO = new RoadwayChangesDAO
      val roadwayDAO = new RoadwayDAO

      roadwayDAO.create(Seq(oldroadway1, oldroadway2, roadway1, roadway2))

      val projId = Sequences.nextViiteProjectId
      val proj = dummyProject(projId, ProjectState.Accepted, List(), None)
      projectDAO.create(proj)

      val changeType = 3 //transfer
      sqlu"""insert into ROADWAY_CHANGES(project_id,change_type,old_road_number,old_road_part_number,old_track,old_start_addr_m,old_end_addr_m,new_road_number,new_road_part_number,new_track,new_start_addr_m,new_end_addr_m,old_discontinuity,new_discontinuity,old_administrative_class,new_administrative_class,old_ely,new_ely, roadway_change_id)
                                  values($projId,$changeType,$roadNumber,$initialRoadPart,0,0,100,$roadNumber,$transferredRoadPart,0,0,100,5,5,1,1,8,8,1)
          """.execute
      sqlu"""insert into ROADWAY_CHANGES(project_id,change_type,old_road_number,old_road_part_number,old_track,old_start_addr_m,old_end_addr_m,new_road_number,new_road_part_number,new_track,new_start_addr_m,new_end_addr_m,old_discontinuity,new_discontinuity,old_administrative_class,new_administrative_class,old_ely,new_ely, roadway_change_id)
                                  values($projId,$changeType,$roadNumber,$initialRoadPart,0,100,200,$roadNumber,$initialRoadPart,0,0,100,5,5,1,1,8,8,2)
          """.execute

      val startValidFromDate = DateTime.now().minusDays(1)
      val endValidFromDate = DateTime.now().plusDays(1)
      val roadwayChangesInfo = roadwayChangesDAO.fetchRoadwayChangesInfo(startValidFromDate, Option(endValidFromDate))
      val changesGroupedById = roadwayChangesInfo.groupBy(_.roadwayChangeId)

      changesGroupedById.values.foreach(changesWithId => changesWithId should have size 1)
    }
  }
}

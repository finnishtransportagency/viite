package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.viite.NewIdValue
import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.UserDefinedCalibrationPoint
import fi.vaylavirasto.viite.model.RoadPart
import fi.vaylavirasto.viite.postgis.DbUtils.runUpdateToDb
import fi.vaylavirasto.viite.postgis.PostGISDatabase.runWithRollback
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import slick.driver.JdbcDriver.backend.Database.dynamicSession  // JdbcBackend#sessionDef
import slick.jdbc.StaticQuery.interpolation

class ProjectCalibrationPointDAOSpec extends AnyFunSuite with Matchers {

  private val mockRoadLinkService = MockitoSugar.mock[RoadLinkService]

  val projectReservedPartDAO = new ProjectReservedPartDAO

  def addTestProjects(): Unit = {
    runUpdateToDb(s"""insert into project (id,state,name,created_by, start_date) VALUES (1,0,'testproject','automatedtest', current_date)""")
    runUpdateToDb(s"""insert into project (id,state,name,created_by, start_date) VALUES (2,0,'testproject2','automatedtest', current_date)""")
  }

  def addProjectRoads(): Unit = {
    projectReservedPartDAO.reserveRoadPart(1, RoadPart(1, 1), "TestUser")
    projectReservedPartDAO.reserveRoadPart(2, RoadPart(2, 1), "TestUser")
    runUpdateToDb(s"""insert into project_link (id,project_id,TRACK,discontinuity_type,road_number,road_part_number,start_addr_M,end_addr_M, original_start_addr_M, original_end_addr_M,created_by,
          SIDE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,LINK_SOURCE) VALUES (1,1,1,0,1,1,1,1,1,1,'automatedtest',
          1, 0, 208.951, 1610995, 0, 1)""")
    runUpdateToDb(s"""insert into project_link (id,project_id,TRACK,discontinuity_type,road_number,road_part_number,start_addr_M,end_addr_M, original_start_addr_M, original_end_addr_M, created_by,
          SIDE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,LINK_SOURCE) VALUES (2,2,1,0,2,1,1,1,1,1,'automatedtest',
          1, 0, 208.951, 1610995, 0, 1)""")
  }

  test("Test createCalibrationPoint of calibration points When creating two calibrations points, Then they should be saved without any problems") {
    runWithRollback {
      addTestProjects()
      addProjectRoads()
      ProjectCalibrationPointDAO.createCalibrationPoint(1, 1, 0.0, 15)
      ProjectCalibrationPointDAO.createCalibrationPoint(UserDefinedCalibrationPoint(NewIdValue, 2, 2, 1.1, 20))
      val calibrationPointsAmmount = sql""" Select count(*) from PROJECT_CALIBRATION_POINT""".as[Long].first
      calibrationPointsAmmount should be (2)
    }
  }

  test("Test findCalibrationPointsOfRoad When adding calibrationPoints if should be returned in the findCalibrationPointById") {
    runWithRollback {
      addTestProjects()
      addProjectRoads()
      ProjectCalibrationPointDAO.createCalibrationPoint(1, 1, 0.0, 15)
      ProjectCalibrationPointDAO.createCalibrationPoint(1, 1, 14.0, 25)
      val calibrationPoints = ProjectCalibrationPointDAO.findCalibrationPointByRemainingValues(1, 1, 0.05, 0.075)
      calibrationPoints.size should be (1)
      calibrationPoints.head.id != NewIdValue should be (true)
      calibrationPoints.head.projectId should be (1)
      val roadCalibrationPoints = ProjectCalibrationPointDAO.findCalibrationPointsOfRoad(1,1)
      roadCalibrationPoints.size should be (2)
      roadCalibrationPoints.head.id should not be roadCalibrationPoints(1).id
      roadCalibrationPoints.head.segmentMValue should not be roadCalibrationPoints(1).segmentMValue
      roadCalibrationPoints.head.addressMValue should not be roadCalibrationPoints(1).addressMValue
      val calibrationPointId = ProjectCalibrationPointDAO.createCalibrationPoint(2, 2, 1.1, 20)
      val foundCalibrationPoint = ProjectCalibrationPointDAO.findCalibrationPointById(calibrationPointId)
      foundCalibrationPoint.isEmpty should be (false)
      foundCalibrationPoint.get.id should be (calibrationPointId)
      foundCalibrationPoint.get.projectId should be (2)
      foundCalibrationPoint.get.projectLinkId should be (2)
      foundCalibrationPoint.get.segmentMValue should be (1.1)
      foundCalibrationPoint.get.addressMValue should be (20)
    }
  }

  test("Test updateSpecificCalibrationPointMeasures When updating calibration point by id Then it should be updated with success") {
    runWithRollback {
      addTestProjects()
      addProjectRoads()
      val id = ProjectCalibrationPointDAO.createCalibrationPoint(1, 1, 0.0, 15)
      ProjectCalibrationPointDAO.updateSpecificCalibrationPointMeasures(id, 1.1, 30)
      val updatedCalibrationPoint = ProjectCalibrationPointDAO.findCalibrationPointById(id).get
      updatedCalibrationPoint.id should be (id)
      updatedCalibrationPoint.segmentMValue should be (1.1)
      updatedCalibrationPoint.addressMValue should be (30)
    }
  }

  test("Test removeSpecificCalibrationPoint When removing calibration pint by id Then it should be removed with success") {
    runWithRollback {
      addTestProjects()
      addProjectRoads()
      val id = ProjectCalibrationPointDAO.createCalibrationPoint(1, 1, 0.0, 15)
      ProjectCalibrationPointDAO.removeSpecificCalibrationPoint(id)
      val nonExistentCalibrationPoint = ProjectCalibrationPointDAO.findCalibrationPointById(id)
      nonExistentCalibrationPoint.isEmpty should be (true)
    }
  }

  test("Test removeAllCalibrationPointsFromProject & removeAllCalibrationPointsFromRoad When removing all calibrations points by project or road Then it should be deleted with success") {
    runWithRollback {
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[String]])).thenReturn(Seq())
      addTestProjects()
      addProjectRoads()
      val id = ProjectCalibrationPointDAO.createCalibrationPoint(1, 1, 0.0, 15)
      val id2 = ProjectCalibrationPointDAO.createCalibrationPoint(1, 1, 14.0, 25)
      ProjectCalibrationPointDAO.removeAllCalibrationPointsFromProject(1)
      val deletedCalibrationPoint1 = ProjectCalibrationPointDAO.findCalibrationPointById(id)
      val deletedCalibrationPoint2 = ProjectCalibrationPointDAO.findCalibrationPointById(id2)
      deletedCalibrationPoint1.isEmpty should be (true)
      deletedCalibrationPoint2.isEmpty should be (true)

      val id3 = ProjectCalibrationPointDAO.createCalibrationPoint(1, 1, 0.0, 15)
      val id4 = ProjectCalibrationPointDAO.createCalibrationPoint(1, 1, 14.0, 25)
      ProjectCalibrationPointDAO.removeAllCalibrationPointsFromRoad(1,1)
      val deletedCalibrationPoint3 = ProjectCalibrationPointDAO.findCalibrationPointById(id3)
      val deletedCalibrationPoint4 = ProjectCalibrationPointDAO.findCalibrationPointById(id4)
      deletedCalibrationPoint3.isEmpty should be (true)
      deletedCalibrationPoint4.isEmpty should be (true)
    }
  }


}

package fi.liikennevirasto.viite.dao

import com.github.tototoshi.slick.MySQLJodaSupport._
import fi.liikennevirasto.digiroad2.asset.LinkGeomSource.ComplimentaryLinkInterface
import fi.liikennevirasto.digiroad2.asset.{BoundingRectangle, LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.{DigiroadEventBus, Point}
import fi.liikennevirasto.viite
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.Discontinuity.Discontinuous
import fi.liikennevirasto.viite.{RoadAddressMerge, RoadAddressService, RoadType}
import org.joda.time.DateTime
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{StaticQuery => Q}

class CalibrationPointDAOSpec extends FunSuite with Matchers {

  val mockRoadLinkService = MockitoSugar.mock[RoadLinkService]
  def runWithRollback(f: => Unit): Unit = {
    Database.forDataSource(OracleDatabase.ds).withDynTransaction {
      f
      dynamicSession.rollback()
    }
  }
  val projectReservedPartDAO = new ProjectReservedPartDAO

  def addTestProjects(): Unit = {
    sqlu"""insert into project (id,state,name,ely,created_by, start_date) VALUES (1,0,'testproject',1,'automatedtest', sysdate)""".execute
    sqlu"""insert into project (id,state,name,ely,created_by, start_date) VALUES (2,0,'testproject2',1,'automatedtest', sysdate)""".execute
  }

  def addProjectRoads(): Unit = {
    projectReservedPartDAO.reserveRoadPart(1, 1, 1, "TestUser")
    projectReservedPartDAO.reserveRoadPart(2, 2, 1, "TestUser")
    sqlu"""insert into project_link (id,project_id,TRACK,discontinuity_type,road_number,road_part_number,start_addr_M,end_addr_M, original_start_addr_M, original_end_addr_M,created_by,
          SIDE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,LINK_SOURCE) VALUES (1,1,1,0,1,1,1,1,1,1,'automatedtest',
          1, 0, 208.951, 1610995, 0, 1)""".execute
    sqlu"""insert into project_link (id,project_id,TRACK,discontinuity_type,road_number,road_part_number,start_addr_M,end_addr_M, original_start_addr_M, original_end_addr_M, created_by,
          SIDE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,LINK_SOURCE) VALUES (2,2,1,0,2,1,1,1,1,1,'automatedtest',
          1, 0, 208.951, 1610995, 0, 1)""".execute
  }

  test("Test createCalibrationPoint of calibration points When creating two calibrations points, Then they should be saved without any problems") {
    runWithRollback {
      addTestProjects()
      addProjectRoads()
      CalibrationPointDAO.createCalibrationPoint(1, 1, 0.0, 15)
      CalibrationPointDAO.createCalibrationPoint(UserDefinedCalibrationPoint(viite.newCalibrationPointId, 2, 2, 1.1, 20))
      val calibrationPointsAmmount = sql""" Select count(*) from PROJECT_CALIBRATION_POINT""".as[Long].first
      calibrationPointsAmmount should be (2)
    }
  }

  test("Test findCalibrationPointsOfRoad When adding calibrationPoints if should be returned in the findCalibrationPointById") {
    runWithRollback {
      addTestProjects()
      addProjectRoads()
      CalibrationPointDAO.createCalibrationPoint(1, 1, 0.0, 15)
      CalibrationPointDAO.createCalibrationPoint(1, 1, 14.0, 25)
      val calibrationPoints = CalibrationPointDAO.findCalibrationPointByRemainingValues(1, 1, 0.05, 0.075)
      calibrationPoints.size should be (1)
      calibrationPoints.head.id != viite.newCalibrationPointId should be (true)
      calibrationPoints.head.projectId should be (1)
      val roadCalibrationPoints = CalibrationPointDAO.findCalibrationPointsOfRoad(1,1)
      roadCalibrationPoints.size should be (2)
      roadCalibrationPoints.head.id should not be roadCalibrationPoints(1).id
      roadCalibrationPoints.head.segmentMValue should not be roadCalibrationPoints(1).segmentMValue
      roadCalibrationPoints.head.addressMValue should not be roadCalibrationPoints(1).addressMValue
      val calibrationPointId = CalibrationPointDAO.createCalibrationPoint(2, 2, 1.1, 20)
      val foundCalibrationPoint = CalibrationPointDAO.findCalibrationPointById(calibrationPointId)
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
      val id = CalibrationPointDAO.createCalibrationPoint(1, 1, 0.0, 15)
      CalibrationPointDAO.updateSpecificCalibrationPointMeasures(id, 1.1, 30)
      val updatedCalibrationPoint = CalibrationPointDAO.findCalibrationPointById(id).get
      updatedCalibrationPoint.id should be (id)
      updatedCalibrationPoint.segmentMValue should be (1.1)
      updatedCalibrationPoint.addressMValue should be (30)
    }
  }

  test("Test removeSpecificCalibrationPoint When removing calibration pint by id Then it should be removed with success") {
    runWithRollback {
      addTestProjects()
      addProjectRoads()
      val id = CalibrationPointDAO.createCalibrationPoint(1, 1, 0.0, 15)
      CalibrationPointDAO.removeSpecificCalibrationPoint(id)
      val nonExistantCalibrationPoint = CalibrationPointDAO.findCalibrationPointById(id)
      nonExistantCalibrationPoint.isEmpty should be (true)
    }
  }

  test("Test removeAllCalibrationPointsFromProject & removeAllCalibrationPointsFromRoad When removing all calibrations points by project or road Then it should be deleted with success") {
    runWithRollback {
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
      addTestProjects()
      addProjectRoads()
      val id = CalibrationPointDAO.createCalibrationPoint(1, 1, 0.0, 15)
      val id2 = CalibrationPointDAO.createCalibrationPoint(1, 1, 14.0, 25)
      CalibrationPointDAO.removeAllCalibrationPointsFromProject(1)
      val deletedCalibrationPoint1 = CalibrationPointDAO.findCalibrationPointById(id)
      val deletedCalibrationPoint2 = CalibrationPointDAO.findCalibrationPointById(id2)
      deletedCalibrationPoint1.isEmpty should be (true)
      deletedCalibrationPoint2.isEmpty should be (true)

      val id3 = CalibrationPointDAO.createCalibrationPoint(1, 1, 0.0, 15)
      val id4 = CalibrationPointDAO.createCalibrationPoint(1, 1, 14.0, 25)
      CalibrationPointDAO.removeAllCalibrationPointsFromRoad(1,1)
      val deletedCalibrationPoint3 = CalibrationPointDAO.findCalibrationPointById(id)
      val deletedCalibrationPoint4 = CalibrationPointDAO.findCalibrationPointById(id2)
      deletedCalibrationPoint3.isEmpty should be (true)
      deletedCalibrationPoint4.isEmpty should be (true)
    }
  }


}

package fi.liikennevirasto.viite.dao

import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}
import fi.vaylavirasto.viite.dao.BaseDAO
import fi.vaylavirasto.viite.model.RoadPart

object ProjectCalibrationPointDAO extends BaseDAO {

  trait CalibrationPointMValues {
    def segmentMValue: Double

    def addressMValue: Long
  }

  trait BaseCalibrationPoint extends CalibrationPointMValues {
    def linkId(): String
  }

  case class UserDefinedCalibrationPoint(id: Long, projectLinkId: Long, projectId: Long, segmentMValue: Double, addressMValue: Long) extends CalibrationPointMValues

  implicit val getCalibrationPoint: GetResult[UserDefinedCalibrationPoint] = new GetResult[UserDefinedCalibrationPoint] {
    def apply(r: PositionedResult) = {

      val id = r.nextLong()
      val projectLinkId = r.nextLong()
      val projectId = r.nextLong()
      val segmentMValue = r.nextDouble()
      val addressMValue = r.nextLong()

      UserDefinedCalibrationPoint(id, projectLinkId, projectId, segmentMValue, addressMValue)
    }

  }


  def findCalibrationPointById(id: Long): Option[UserDefinedCalibrationPoint] = {
    val baseQuery =
      s"""
         Select * From PROJECT_CALIBRATION_POINT Where ID = $id
       """

    val tuples = Q.queryNA[UserDefinedCalibrationPoint](baseQuery).list
    tuples.groupBy(_.id).map {
      case (_, calibrationPointList) => calibrationPointList.head
    }.toList.headOption
  }

  def findCalibrationPointByRemainingValues(projectLinkId: Long, projectId: Long, segmentMValue: Double, epsilon: Double = 0.1): Seq[UserDefinedCalibrationPoint] = {
    val baseQuery =
      s"""
         Select ID, PROJECT_LINK_ID, PROJECT_ID, LINK_M, ADDRESS_M From PROJECT_CALIBRATION_POINT Where
         PROJECT_LINK_ID = $projectLinkId And PROJECT_ID = $projectId And ABS(LINK_M - $segmentMValue) < $epsilon
       """

    Q.queryNA[(Long, Long, Long, Double, Long)](baseQuery).list.map {
      case (id, linkId, prId, linkM, addressM) => UserDefinedCalibrationPoint(id, linkId, prId, linkM, addressM)
    }
  }

  def findEndCalibrationPoint(projectLinkId: Long, projectId: Long): Seq[UserDefinedCalibrationPoint] = {
    val baseQuery =
      s"""
         Select ID, PROJECT_LINK_ID, PROJECT_ID, LINK_M, ADDRESS_M From PROJECT_CALIBRATION_POINT Where PROJECT_LINK_ID = $projectLinkId And PROJECT_ID = $projectId
         And ADDRESS_M = (Select Max(Address_M) from PROJECT_CALIBRATION_POINT Where PROJECT_LINK_ID = $projectLinkId And PROJECT_ID = $projectId)
       """

    Q.queryNA[(Long, Long, Long, Double, Long)](baseQuery).list.map {
      case (id, linkId, prId, linkM, addressM) => UserDefinedCalibrationPoint(id, linkId, prId, linkM, addressM)
    }
  }

  def findCalibrationPointsOfRoad(projectId: Long, projectLinkId: Long): Seq[UserDefinedCalibrationPoint] = {
    val baseQuery =
      s"""
         Select ID, PROJECT_LINK_ID, PROJECT_ID, LINK_M, ADDRESS_M From PROJECT_CALIBRATION_POINT Where PROJECT_LINK_ID = $projectLinkId And PROJECT_ID = $projectId
       """

    Q.queryNA[(Long, Long, Long, Double, Long)](baseQuery).list.map {
      case (id, projectLinkId, prId, linkM, addressM) => UserDefinedCalibrationPoint(id, projectLinkId, prId, linkM, addressM)
    }
  }

  def fetchByRoadPart(projectId: Long, roadPart: RoadPart): Seq[UserDefinedCalibrationPoint] = {
    val baseQuery =
      s"""
         Select PROJECT_CALIBRATION_POINT.ID, PROJECT_LINK_ID, pl.PROJECT_ID, LINK_M, ADDRESS_M From PROJECT_CALIBRATION_POINT JOIN PROJECT_LINK pl
           ON (pl.ID = PROJECT_CALIBRATION_POINT.PROJECT_LINK_ID)
         WHERE pl.ROAD_NUMBER = ${roadPart.roadNumber} AND pl.ROAD_PART_NUMBER = ${roadPart.partNumber}
         AND pl.PROJECT_ID = $projectId
       """

    Q.queryNA[(Long, Long, Long, Double, Long)](baseQuery).list.map {
      case (id, projectLinkId, prId, linkM, addressM) => UserDefinedCalibrationPoint(id, projectLinkId, prId, linkM, addressM)
    }
  }

  def createCalibrationPoint(calibrationPoint: UserDefinedCalibrationPoint): Long = {
    val nextCalibrationPointId = sql"""select nextval('PROJECT_CAL_POINT_ID_SEQ')""".as[Long].first
    runUpdateToDb(s"""
      Insert Into PROJECT_CALIBRATION_POINT (ID, PROJECT_LINK_ID, PROJECT_ID, LINK_M, ADDRESS_M)
      Values ($nextCalibrationPointId, ${calibrationPoint.projectLinkId}, ${calibrationPoint.projectId}, ${calibrationPoint.segmentMValue}, ${calibrationPoint.addressMValue})
      """)
    nextCalibrationPointId
  }

  def createCalibrationPoint(projectLinkId: Long, projectId: Long, segmentMValue: Double, addressMValue: Long): Long = {
    val nextCalibrationPointId = sql"""select nextval('PROJECT_CAL_POINT_ID_SEQ')""".as[Long].first
    runUpdateToDb(s"""
      Insert Into PROJECT_CALIBRATION_POINT (ID, PROJECT_LINK_ID, PROJECT_ID, LINK_M, ADDRESS_M)
      Values ($nextCalibrationPointId, $projectLinkId, $projectId, $segmentMValue, $addressMValue)
      """)
    nextCalibrationPointId
  }

  def updateSpecificCalibrationPointMeasures(id: Long, segmentMValue: Double, addressMValue: Long): Unit = {
    runUpdateToDb(s"""
        Update PROJECT_CALIBRATION_POINT
           Set LINK_M = $segmentMValue, ADDRESS_M = $addressMValue
         Where ID = $id
      """)
  }

  def removeSpecificCalibrationPoint(id: Long): Unit = {
    runUpdateToDb(s"""
        Delete From PROJECT_CALIBRATION_POINT
         Where ID = $id
      """)
  }

  def removeAllCalibrationPointsFromRoad(projectLinkId: Long, projectId: Long): Unit = {
    runUpdateToDb(s"""
        Delete From PROJECT_CALIBRATION_POINT
         Where PROJECT_LINK_ID = $projectLinkId And PROJECT_ID = $projectId
      """)
  }

  def removeAllCalibrationPoints(projectLinkIds: Set[Long]): Unit = {
    if (projectLinkIds.nonEmpty)
      runUpdateToDb(s"""
        Delete From PROJECT_CALIBRATION_POINT
         Where PROJECT_LINK_ID in (${projectLinkIds.mkString(",")})
      """)
  }

  def removeAllCalibrationPointsFromProject(projectId: Long): Unit = {
    runUpdateToDb(s"""
        Delete From PROJECT_CALIBRATION_POINT
        Where PROJECT_ID  = $projectId
      """)
  }

}

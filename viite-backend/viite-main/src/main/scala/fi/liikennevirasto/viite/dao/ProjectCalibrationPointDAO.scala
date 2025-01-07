package fi.liikennevirasto.viite.dao

import scalikejdbc._
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

  object UserDefinedCalibrationPoint extends SQLSyntaxSupport[UserDefinedCalibrationPoint] {
    override val tableName = "PROJECT_CALIBRATION_POINT"
    def apply(rs: WrappedResultSet): UserDefinedCalibrationPoint = UserDefinedCalibrationPoint(
      id = rs.long("ID"),
      projectLinkId = rs.long("PROJECT_LINK_ID"),
      projectId = rs.long("PROJECT_ID"),
      segmentMValue = rs.double("LINK_M"),
      addressMValue = rs.long("ADDRESS_M")
    )
  }

  private def queryList(query: SQL[Nothing, NoExtractor]): List[UserDefinedCalibrationPoint] = {
    runSelectQuery(query.map(UserDefinedCalibrationPoint.apply))
      .groupBy(_.id)
      .map { case (_, points) => points.head }
      .toList
  }


  def findCalibrationPointById(id: Long): Option[UserDefinedCalibrationPoint] = {
    val query =
      sql"""
          Select * From PROJECT_CALIBRATION_POINT
          Where ID = $id
       """

    runSelectSingleOption(query.map(UserDefinedCalibrationPoint.apply))
  }

  def findCalibrationPointByRemainingValues(projectLinkId: Long, projectId: Long, segmentMValue: Double, epsilon: Double = 0.1): Seq[UserDefinedCalibrationPoint] = {
    val query =
      sql"""
         Select ID, PROJECT_LINK_ID, PROJECT_ID, LINK_M, ADDRESS_M
         From PROJECT_CALIBRATION_POINT
         Where PROJECT_LINK_ID = $projectLinkId
         And PROJECT_ID = $projectId
         And ABS(LINK_M - $segmentMValue) < $epsilon
       """

    queryList(query)
  }

  def findEndCalibrationPoint(projectLinkId: Long, projectId: Long): Seq[UserDefinedCalibrationPoint] = {
    val query =
      sql"""
         Select ID, PROJECT_LINK_ID, PROJECT_ID, LINK_M, ADDRESS_M
         From PROJECT_CALIBRATION_POINT
         Where PROJECT_LINK_ID = $projectLinkId
         And PROJECT_ID = $projectId
         And ADDRESS_M = (
                          Select Max(Address_M)
                          from PROJECT_CALIBRATION_POINT
                          Where PROJECT_LINK_ID = $projectLinkId
                          And PROJECT_ID = $projectId
                          )
       """

    queryList(query)
  }

  def findCalibrationPointsOfRoad(projectId: Long, projectLinkId: Long): Seq[UserDefinedCalibrationPoint] = {
    val query =
      sql"""
         Select ID, PROJECT_LINK_ID, PROJECT_ID, LINK_M, ADDRESS_M
         From PROJECT_CALIBRATION_POINT
         Where PROJECT_LINK_ID = $projectLinkId
         And PROJECT_ID = $projectId
       """

    queryList(query)
  }

  def fetchByRoadPart(projectId: Long, roadPart: RoadPart): Seq[UserDefinedCalibrationPoint] = {
    val query =
      sql"""
         Select PROJECT_CALIBRATION_POINT.ID, PROJECT_LINK_ID, pl.PROJECT_ID, LINK_M, ADDRESS_M
         From PROJECT_CALIBRATION_POINT
         JOIN PROJECT_LINK pl
           ON (pl.ID = PROJECT_CALIBRATION_POINT.PROJECT_LINK_ID)
         WHERE pl.ROAD_NUMBER = ${roadPart.roadNumber} AND pl.ROAD_PART_NUMBER = ${roadPart.partNumber}
         AND pl.PROJECT_ID = $projectId
       """

    queryList(query)
  }

  def createCalibrationPoint(calibrationPoint: UserDefinedCalibrationPoint): Long = {
    val nextCalibrationPointId = runSelectSingleFirstOptionWithType[Long](sql"""select nextval('PROJECT_CAL_POINT_ID_SEQ')""").getOrElse(
      throw new IllegalStateException("Could not get next sequence value for calibration point")
    )
    runUpdateToDb(sql"""
      Insert Into PROJECT_CALIBRATION_POINT (ID, PROJECT_LINK_ID, PROJECT_ID, LINK_M, ADDRESS_M)
      Values ($nextCalibrationPointId, ${calibrationPoint.projectLinkId}, ${calibrationPoint.projectId}, ${calibrationPoint.segmentMValue}, ${calibrationPoint.addressMValue})
      """)
    nextCalibrationPointId
  }

  def createCalibrationPoint(projectLinkId: Long, projectId: Long, segmentMValue: Double, addressMValue: Long): Long = {
    val nextCalibrationPointId = runSelectSingleFirstOptionWithType[Long](sql"""select nextval('PROJECT_CAL_POINT_ID_SEQ')""").getOrElse(
      throw new IllegalStateException("Could not get next sequence value for calibration point")
    )
    runUpdateToDb(sql"""
      Insert Into PROJECT_CALIBRATION_POINT (ID, PROJECT_LINK_ID, PROJECT_ID, LINK_M, ADDRESS_M)
      Values ($nextCalibrationPointId, $projectLinkId, $projectId, $segmentMValue, $addressMValue)
      """)
    nextCalibrationPointId
  }

  def updateSpecificCalibrationPointMeasures(id: Long, segmentMValue: Double, addressMValue: Long): Unit = {
    runUpdateToDb(sql"""
        Update PROJECT_CALIBRATION_POINT
           Set LINK_M = $segmentMValue, ADDRESS_M = $addressMValue
         Where ID = $id
      """)
  }

  def removeSpecificCalibrationPoint(id: Long): Unit = {
    runUpdateToDb(sql"""
        Delete From PROJECT_CALIBRATION_POINT
         Where ID = $id
      """)
  }

  def removeAllCalibrationPointsFromRoad(projectLinkId: Long, projectId: Long): Unit = {
    runUpdateToDb(sql"""
        Delete From PROJECT_CALIBRATION_POINT
         Where PROJECT_LINK_ID = $projectLinkId And PROJECT_ID = $projectId
      """)
  }

  def removeAllCalibrationPoints(projectLinkIds: Set[Long]): Unit = {
    if (projectLinkIds.nonEmpty)
      runUpdateToDb(sql"""
        Delete From PROJECT_CALIBRATION_POINT
         Where PROJECT_LINK_ID in ($projectLinkIds)
      """)
  }

  def removeAllCalibrationPointsFromProject(projectId: Long): Unit = {
    runUpdateToDb(sql"""
        Delete From PROJECT_CALIBRATION_POINT
        Where PROJECT_ID  = $projectId
      """)
  }

}

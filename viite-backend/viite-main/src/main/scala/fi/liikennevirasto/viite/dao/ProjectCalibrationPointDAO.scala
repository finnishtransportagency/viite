package fi.liikennevirasto.viite.dao

import scalikejdbc._
import fi.vaylavirasto.viite.dao.{BaseDAO, Sequences}
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
    override val tableName = "project_calibration_point"
    def apply(rs: WrappedResultSet): UserDefinedCalibrationPoint = UserDefinedCalibrationPoint(
      id            = rs.long("id"),
      projectLinkId = rs.long("project_link_id"),
      projectId     = rs.long("project_id"),
      segmentMValue = rs.double("link_m"),
      addressMValue = rs.long("address_m")
    )
  }

  private def queryList(query: SQL[Nothing, NoExtractor]): List[UserDefinedCalibrationPoint] = {
    runSelectQuery(query.map(UserDefinedCalibrationPoint.apply))
      .groupBy(_.id)
      .map { case (_, points) => points.head }
      .toList
  }

  lazy val selectAllFromProjectCalibrationPoint =
    sqls"""
          SELECT id, project_link_id, project_id, link_m, address_m
          FROM project_calibration_point
        """

  def findCalibrationPointById(id: Long): Option[UserDefinedCalibrationPoint] = {
    val query =
      sql"""
          $selectAllFromProjectCalibrationPoint
          WHERE id = $id
       """

    runSelectSingleOption(query.map(UserDefinedCalibrationPoint.apply))
  }

  def findCalibrationPointByRemainingValues(projectLinkId: Long, projectId: Long, segmentMValue: Double, epsilon: Double = 0.1): Seq[UserDefinedCalibrationPoint] = {
    val query =
      sql"""
         $selectAllFromProjectCalibrationPoint
         WHERE project_link_id = $projectLinkId
         AND project_id = $projectId
         AND ABS(link_m - $segmentMValue) < $epsilon
       """

    queryList(query)
  }

  def findEndCalibrationPoint(projectLinkId: Long, projectId: Long): Seq[UserDefinedCalibrationPoint] = {
    val query =
      sql"""
         $selectAllFromProjectCalibrationPoint
         WHERE project_link_id = $projectLinkId
         AND project_id = $projectId
         AND address_m = (
                          SELECT Max(Address_M)
                          FROM project_calibration_point
                          WHERE project_link_id = $projectLinkId
                          AND project_id = $projectId
                          )
       """

    queryList(query)
  }

  def findCalibrationPointsOfRoad(projectId: Long, projectLinkId: Long): Seq[UserDefinedCalibrationPoint] = {
    val query =
      sql"""
         $selectAllFromProjectCalibrationPoint
         WHERE project_link_id = $projectLinkId
         AND project_id = $projectId
       """

    queryList(query)
  }

  def fetchByRoadPart(projectId: Long, roadPart: RoadPart): Seq[UserDefinedCalibrationPoint] = {
    val query =
      sql"""
         SELECT project_calibration_point.id, project_link_id, pl.project_id, link_m, address_m
         FROM project_calibration_point
         JOIN project_link pl ON (pl.id = project_calibration_point.project_link_id)
         WHERE pl.road_number = ${roadPart.roadNumber}
         AND pl.road_part_number = ${roadPart.partNumber}
         AND pl.project_id = $projectId
       """

    queryList(query)
  }

  def createCalibrationPoint(calibrationPoint: UserDefinedCalibrationPoint): Long = {
    val nextCalibrationPointId = Sequences.nextProjectCalibrationPointId

    runUpdateToDb(sql"""
      INSERT INTO project_calibration_point (id, project_link_id, project_id, link_m, address_m)
      VALUES ($nextCalibrationPointId, ${calibrationPoint.projectLinkId}, ${calibrationPoint.projectId}, ${calibrationPoint.segmentMValue}, ${calibrationPoint.addressMValue})
      """)
    nextCalibrationPointId
  }

  def createCalibrationPoint(projectLinkId: Long, projectId: Long, segmentMValue: Double, addressMValue: Long): Long = {
    val nextCalibrationPointId = Sequences.nextProjectCalibrationPointId

    runUpdateToDb(sql"""
      INSERT INTO project_calibration_point (id, project_link_id, project_id, link_m, address_m)
      VALUES ($nextCalibrationPointId, $projectLinkId, $projectId, $segmentMValue, $addressMValue)
      """)
    nextCalibrationPointId
  }

  def updateSpecificCalibrationPointMeasures(id: Long, segmentMValue: Double, addressMValue: Long): Unit = {
    runUpdateToDb(sql"""
        UPDATE project_calibration_point
        SET link_m = $segmentMValue, address_m = $addressMValue
        WHERE id = $id
      """)
  }

  def removeSpecificCalibrationPoint(id: Long): Unit = {
    runUpdateToDb(sql"""
        DELETE FROM project_calibration_point
        WHERE id = $id
      """)
  }

  def removeAllCalibrationPointsFromRoad(projectLinkId: Long, projectId: Long): Unit = {
    runUpdateToDb(sql"""
        DELETE FROM project_calibration_point
        WHERE project_link_id = $projectLinkId AND project_id = $projectId
      """)
  }

  def removeAllCalibrationPoints(projectLinkIds: Set[Long]): Unit = {
    if (projectLinkIds.nonEmpty)
      runUpdateToDb(sql"""
        DELETE FROM project_calibration_point
        WHERE project_link_id IN ($projectLinkIds)
      """)
  }

  def removeAllCalibrationPointsFromProject(projectId: Long): Unit = {
    runUpdateToDb(sql"""
        DELETE FROM project_calibration_point
        WHERE project_id  = $projectId
      """)
  }

}

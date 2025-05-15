package fi.vaylavirasto.viite.dao

import scalikejdbc._

case class ProjectLinkName(id: Long, projectId: Long, roadNumber: Long, roadName: String)

object ProjectLinkName extends SQLSyntaxSupport[ProjectLinkName] {
  override val tableName = "PROJECT_LINK_NAME"

  def apply(rs: WrappedResultSet): ProjectLinkName = ProjectLinkName(
    id         = rs.long("id"),
    projectId  = rs.long("project_id"),
    roadNumber = rs.long("road_number"),
    roadName   = rs.string("road_name")
  )
}

object ProjectLinkNameDAO extends BaseDAO {
  private val pln = ProjectLinkName.syntax("pln")

  private val selectAllFromPLinkName =
    sqls"""
          SELECT ${pln.id}, ${pln.projectId}, ${pln.roadNumber}, ${pln.roadName}
          FROM ${ProjectLinkName.as(pln)}
    """

  private def queryList (query: SQL[Nothing, NoExtractor]): Seq[ProjectLinkName] = {
    runSelectQuery(query.map(ProjectLinkName.apply))
  }

  def get(roadNumber: Long, projectId: Long): Option[ProjectLinkName] = {
    queryList(
      sql"""
        $selectAllFromPLinkName
        WHERE ${pln.roadNumber} = $roadNumber
        AND project_id = $projectId
        """)
      .headOption
  }

  def get(roadNumbers: Set[Long], projectId: Long): Seq[ProjectLinkName] = {
    queryList(sql"""
                    $selectAllFromPLinkName
                    WHERE road_number IN ($roadNumbers)
                    AND project_id = $projectId
                    """)
  }

  def create(projectLinkNames: Seq[ProjectLinkName]): Unit = {
    val insertQuery =
      sql"""
            INSERT INTO project_link_name (id, project_id, road_number, road_name)
            VALUES (NEXTVAL('project_link_name_seq'), ?, ?, ?)
           """

    val batchParams: Seq[Seq[Any]] = projectLinkNames.map { projectLinkName =>
      Seq(projectLinkName.projectId, projectLinkName.roadNumber, projectLinkName.roadName)
    }

    runBatchUpdateToDb(insertQuery, batchParams)
  }

  def update(projectLinkNames: Seq[ProjectLinkName]): Unit = {
    val updateQuery =
      sql"""
           UPDATE project_link_name
           SET road_name = ?
           WHERE project_id = ? AND road_number = ?
           """

    val batchParams: Seq[Seq[Any]] = projectLinkNames.map { projectLinkName =>
      Seq(
        projectLinkName.roadName,
        projectLinkName.projectId,
        projectLinkName.roadNumber
      )
    }

    runBatchUpdateToDb(updateQuery, batchParams)
  }

  def create(projectId: Long, roadNumber: Long, roadName: String): Unit = {
    runUpdateToDb(sql"""
         INSERT INTO project_link_name (id, project_id, road_number, road_name)
         VALUES (nextval('project_link_name_seq'), $projectId, $roadNumber, $roadName)
    """)
  }

  def update(projectId: Long, roadNumber: Long, roadName: String): Unit = {
    runUpdateToDb(sql"""
         UPDATE project_link_name set road_name = $roadName
         WHERE project_id = $projectId and road_number = $roadNumber
    """)
  }

  def update(id: Long, roadName: String): Unit = {
    runUpdateToDb(sql"""
         UPDATE project_link_name set road_name = $roadName WHERE id = $id
    """)
  }

  def revert(roadNumber: Long, projectId: Long): Unit = {
    runUpdateToDb(sql"""
        DELETE FROM project_link_name
        WHERE road_number = $roadNumber and project_id = $projectId and (select count(distinct(ROAD_PART_NUMBER))
        FROM project_link WHERE road_number = $roadNumber and status != 0) < 2
    """)
  }

  def removeByProject(projectId: Long): Unit = {
    runUpdateToDb(sql"""DELETE FROM PROJECT_LINK_NAME WHERE PROJECT_ID = $projectId""")
  }


}

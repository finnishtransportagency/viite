package fi.vaylavirasto.viite.dao
import scalikejdbc._


object ProjectLinkNameScalike extends SQLSyntaxSupport[ProjectLinkName] {
  override val tableName = "PROJECT_LINK_NAME"

  def apply(rs: WrappedResultSet): ProjectLinkName = ProjectLinkName(
    id = rs.long("id"),
    projectId = rs.long("project_id"),
    roadNumber = rs.long("road_number"),
    roadName = rs.string("road_name")
  )
}

object ProjectLinkNameScalikeDAO extends ScalikeJDBCBaseDAO {
  private val pln = ProjectLinkNameScalike.syntax("pln")

  private val selectAllFromPLinkName =
    sqls"""
          SELECT ${pln.id}, ${pln.projectId}, ${pln.roadNumber}, ${pln.roadName}
          FROM ${ProjectLinkNameScalike.as(pln)}
    """

  private def queryList (query: SQLSyntax): Seq[ProjectLinkName] = {
    runSelectQuery(sql"$query".map(ProjectLinkNameScalike.apply))
  }

  def get(roadNumber: Long, projectId: Long): Option[ProjectLinkName] = {
    queryList(
      sqls"""
        $selectAllFromPLinkName
        WHERE ${pln.roadNumber} = $roadNumber
        AND project_id = $projectId
        """)
      .headOption
  }

}

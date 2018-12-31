package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.viite.dao.RoadNameDAO.getClass
import org.joda.time.DateTime
import org.slf4j.LoggerFactory
import slick.jdbc.{GetResult, PositionedResult}
import org.joda.time.DateTime
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}

case class ProjectLinkName(id: Long, projectId: Long, roadNumber: Long, roadName: String)

object ProjectLinkNameDAO {

  private val logger = LoggerFactory.getLogger(getClass)
  private val ptojectLinkNameQueryBase = s"""select id, project_id, road_number, road_name from project_link_name """

  implicit val getProjectLinkNameRow = new GetResult[ProjectLinkName] {
    def apply(r: PositionedResult) = {
      val id = r.nextLong()
      val projectId = r.nextLong()
      val roadNumber = r.nextLong()
      val roadName = r.nextString()

      ProjectLinkName(id, projectId, roadNumber, roadName)
    }
  }

  private def queryList(query: String) = {
    Q.queryNA[ProjectLinkName](ptojectLinkNameQueryBase + query).iterator.toSeq
  }

  def get(roadNumber: Long, projectId: Long): Option[ProjectLinkName] = {
    queryList(s"where road_number = $roadNumber and project_id = $projectId").headOption
  }

  def get(roadNumbers: Set[Long], projectId: Long): Seq[ProjectLinkName] = {
    val roadNumbersStr = roadNumbers.mkString(",")
    queryList(s"where road_number in ($roadNumbersStr) and project_id = $projectId")
  }

  def create(projectLinkNames: Seq[ProjectLinkName]): Unit = {
    val projectLinkNamePS = dynamicSession.prepareStatement("insert into project_link_name (id, project_id, road_number, road_name) values values (project_link_name_seq.nextval, ?, ?, ?)")
    projectLinkNames.foreach { projectLinkName =>
      projectLinkNamePS.setLong(1, projectLinkName.projectId)
      projectLinkNamePS.setLong(2, projectLinkName.roadNumber)
      projectLinkNamePS.setString(3, projectLinkName.roadName)
      projectLinkNamePS.addBatch()
    }
    projectLinkNamePS.executeBatch()
    projectLinkNamePS.close()
  }

  def update(projectLinkNames: Seq[ProjectLinkName]): Unit = {
    val projectLinkNamePS = dynamicSession.prepareStatement("update project_link_name set road_name = ? where project_id = ? and road_number = ?")
    projectLinkNames.foreach { projectLinkName =>
      projectLinkNamePS.setString(1, projectLinkName.roadName)
      projectLinkNamePS.setLong(2, projectLinkName.projectId)
      projectLinkNamePS.setLong(3, projectLinkName.roadNumber)
      projectLinkNamePS.addBatch()
    }
    projectLinkNamePS.executeBatch()
    projectLinkNamePS.close()
  }

  def create(projectId: Long, roadNumber: Long, roadName: String): Unit = {
    sqlu"""
         insert into project_link_name (id, project_id, road_number, road_name)
         values (project_link_name_seq.nextval, $projectId, $roadNumber, $roadName)
    """.execute
  }

  def update(projectId: Long, roadNumber: Long, roadName: String): Unit = {
    sqlu"""
         update project_link_name set road_name = $roadName where project_id = $projectId and road_number = $roadNumber
    """.execute
  }

  def update(id: Long, roadName: String): Unit = {
    sqlu"""
         update project_link_name set road_name = $roadName where id = $id
    """.execute
  }

  def revert(roadNumber: Long, projectId: Long): Unit = {
    sqlu"""
        delete from project_link_name where road_number = $roadNumber and project_id = $projectId and (select count(distinct(ROAD_PART_NUMBER)) from project_link where road_number = $roadNumber and status != 0) < 2
    """.execute
  }

  def removeByProject(projectId: Long): Unit = {
    sqlu"""DELETE FROM PROJECT_LINK_NAME WHERE PROJECT_ID = ${projectId}""".execute
  }
}

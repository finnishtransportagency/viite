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
  private val ptojectLinkNameQueryBase =  s"""select id,project_id,road_number,road_name from project_link_name """
  implicit val getRoadNameRow = new GetResult[ProjectLinkName] {
    def apply(r: PositionedResult) = {
      val roadNameId = r.nextLong()
      val projectId = r.nextLong()
      val roadNumber = r.nextLong()
      val roadName = r.nextString()

      ProjectLinkName(roadNameId, projectId, roadNumber, roadName)
    }
  }

  private def queryList(query: String) = {
    Q.queryNA[ProjectLinkName](query).iterator.toSeq
  }

  def getProjectLinkNameByRoadNumber(roadNumber: Long, projectId: Long): Option[ProjectLinkName] = {
    queryList(s"where road_number = $roadNumber and project_id = $projectId").headOption
  }

  def create(projectId: Long, roadNumber: Long, roadName: String) : Unit = {
    sqlu"""
         insert into project_link_name (id, project_id, road_number, road_name)
         values (project_link_name_seq.nextval, $projectId, $roadNumber, $roadName)
    """.execute
  }

  def update(projectId: Long, roadNumber: Long, roadName: String) : Unit = {
    sqlu"""
         update project_link_name set road_name = $roadName where project_id = $projectId and road_number = $roadNumber
    """.execute
  }

  def update(id: Long, roadName: String) : Unit = {
    sqlu"""
         update project_link_name set road_name = $roadName where id = $id
    """.execute
  }

  def removeProjectLinkName(roadNumber: Long, projectId: Long): Unit = {
    sqlu"""DELETE FROM PROJECT_LINK_NAME WHERE ROAD_NUMBER = ${roadNumber} AND PROJECT_ID = ${projectId}""".execute
  }

}

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
  private val ptojectLinkNameQueryBase =  s"""select id,project_id,road_number,road_Name from project_link_name """
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
    Q.queryNA[RoadName](query).iterator.toSeq
  }

  def getProjectLinkNameByRoadNumber(roadNumber: Long): Option[ProjectLinkName] = {
    queryList(s"where road_number = $roadNumber").headOption
  }

}

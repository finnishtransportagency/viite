package fi.liikennevirasto.viite.dao

import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation

object ProjectActionHistoryDAO {

  def create(projectId: Long, actionType: String, username: String, payload: Option[String] = None) = {
    payload match {
      case Some(payload) if payload.nonEmpty =>
        sqlu"""INSERT INTO project_action_history (project_id, action_type, payload, created_by)
             VALUES ($projectId, $actionType, ${payload}, $username)
           """.execute
      case _ =>
        sqlu"""INSERT INTO project_action_history (project_id, action_type, created_by)
             VALUES ($projectId, $actionType, $username)
           """.execute
    }
  }
}

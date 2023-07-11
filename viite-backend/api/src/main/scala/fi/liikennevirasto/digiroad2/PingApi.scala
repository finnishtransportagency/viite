package fi.liikennevirasto.digiroad2

import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.vaylavirasto.viite.dao.PingDAO
import org.scalatra.{InternalServerError, Ok, ScalatraServlet}
import org.slf4j.LoggerFactory

class PingApi extends ScalatraServlet {
  private val logger = LoggerFactory.getLogger(getClass)

  def withDynSession[T](f: => T): T = PostGISDatabase.withDynSession(f)

  get("/") {
    withDynSession {
      try {
        val dbTime = PingDAO.getDbTime
        Ok(s"OK (DB Time: $dbTime)\n")
      } catch {
        case e: Exception =>
          logger.error("Ping failed. DB connection error.", e)
          InternalServerError("Ping failed. DB connection error.")
      }
    }
  }

}

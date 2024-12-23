package fi.liikennevirasto.digiroad2

import fi.vaylavirasto.viite.dao.PingDAO
import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC
import org.scalatra.{InternalServerError, Ok, ScalatraServlet}
import org.slf4j.LoggerFactory

class PingApi extends ScalatraServlet {
  private val logger = LoggerFactory.getLogger(getClass)

  get("/") {
    PostGISDatabaseScalikeJDBC.runWithReadOnlyImplicitSession { implicit session =>
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

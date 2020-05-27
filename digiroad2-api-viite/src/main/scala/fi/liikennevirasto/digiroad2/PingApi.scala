package fi.liikennevirasto.digiroad2

import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.viite.dao.PingDAO
import org.scalatra.{InternalServerError, Ok, ScalatraServlet}
import org.slf4j.LoggerFactory

class PingApi extends ScalatraServlet {
  val logger = LoggerFactory.getLogger(getClass)

  def withDynSession[T](f: => T): T = OracleDatabase.withDynSession(f)

  get("/") {
    withDynSession {
      try {
        val dbTime = PingDAO.getDbTime()
        Ok(s"OK (DB Time: $dbTime)\n")
      } catch {
        case e: Exception =>
          logger.error("Ping failed. DB connection error.", e)
          InternalServerError("Ping failed. DB connection error.")
      }
    }
  }

}

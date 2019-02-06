package fi.liikennevirasto.digiroad2

import org.scalatra.{InternalServerError, Ok, ScalatraServlet}
import org.slf4j.LoggerFactory
import org.scalatra.swagger._

class PingApi(implicit val swagger: Swagger) extends ScalatraServlet with SwaggerSupport {

  val logger = LoggerFactory.getLogger(getClass)

  protected val applicationDescription = "The PingAPI"

  val ping = (
    apiOperation[Unit]("ping")
    tags "PingAPI"
    summary "This allows viite to respond to a simple, empty get request in order to receive a confirmation of Viite being active or not."
    notes ""
  )

  get("/", operation(ping)) {
    try {
      Digiroad2Context.userProvider.getUser("")
      Ok("OK")
    } catch {
      case e: Exception =>
        logger.error("DB connection error", e)
        InternalServerError("Database ping failed")
    }
  }
}

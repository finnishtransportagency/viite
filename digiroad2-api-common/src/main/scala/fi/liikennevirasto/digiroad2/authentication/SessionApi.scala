package fi.liikennevirasto.digiroad2.authentication

import org.scalatra._
import fi.liikennevirasto.digiroad2.Digiroad2Context._
import org.slf4j.LoggerFactory

class SessionApi extends ScalatraServlet {
  val logger = LoggerFactory.getLogger(getClass)

  before() {
    response.setHeader(Digiroad2ServerOriginatedResponseHeader, "true")
  }

  post("/session") {
    val username = request.getParameter("username")
    cookies.set("testusername", username)
    redirect(url("index.html"))
  }

}
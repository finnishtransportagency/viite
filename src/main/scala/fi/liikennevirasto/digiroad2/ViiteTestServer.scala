package fi.liikennevirasto.digiroad2

import org.eclipse.jetty.webapp.WebAppContext

object ViiteTestServer extends App with DigiroadServer {
  override val viiteContextPath: String = "/viite"

  override def createViiteContext(): WebAppContext = {
    val context = super.createViiteContext()
    context.addServlet(classOf[ViiteTierekisteriMockApi], "/trrest/*")
    context
  }

  startServer()
}

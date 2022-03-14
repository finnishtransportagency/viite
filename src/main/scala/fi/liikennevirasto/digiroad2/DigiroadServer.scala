package fi.liikennevirasto.digiroad2

import org.eclipse.jetty.jmx.MBeanContainer
import org.eclipse.jetty.server.handler.ContextHandlerCollection
import org.eclipse.jetty.server.{Handler, Server}
import org.eclipse.jetty.webapp.WebAppContext

import java.lang.management.ManagementFactory

trait DigiroadServer {
  val viiteContextPath: String

  def startServer() {
    val server = new Server(9080)
    val handler = new ContextHandlerCollection()
    val mbContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer)
    server.addEventListener(mbContainer)
    server.addBean(mbContainer)
    val handlers = Array(createViiteContext())
    handler.setHandlers(handlers.map(_.asInstanceOf[Handler]))
    server.setHandler(handler)
    server.start()
    server.join()
  }

  def createViiteContext(): WebAppContext = {
    val appContext = new WebAppContext()
    appContext.setDescriptor("src/main/webapp/WEB-INF/web.xml")
    appContext.setResourceBase("src/main/webapp/viite")
    appContext.setContextPath(viiteContextPath)
    appContext.setParentLoaderPriority(true)
    appContext.setInitParameter("org.eclipse.jetty.servlet.Default.dirAllowed", "false")
    appContext.getMimeTypes.addMimeMapping("ttf", "application/x-font-ttf")
    appContext.getMimeTypes.addMimeMapping("woff", "application/x-font-woff")
    appContext.getMimeTypes.addMimeMapping("eot", "application/vnd.ms-fontobject")
    appContext.getMimeTypes.addMimeMapping("js", "application/javascript; charset=UTF-8")
    appContext
  }
}

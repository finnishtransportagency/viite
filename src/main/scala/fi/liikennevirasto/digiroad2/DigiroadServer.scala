package fi.liikennevirasto.digiroad2

import java.lang.management.ManagementFactory

import fi.liikennevirasto.digiroad2.util.ViiteProperties
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}
import org.eclipse.jetty.client.api.Request
import org.eclipse.jetty.client.HttpClient
import org.eclipse.jetty.jmx.MBeanContainer
import org.eclipse.jetty.proxy.ProxyServlet
import org.eclipse.jetty.server._
import org.eclipse.jetty.server.handler.ContextHandlerCollection
import org.eclipse.jetty.servlet.{DefaultServlet, ServletHolder}
import org.eclipse.jetty.util.ssl.SslContextFactory
import org.eclipse.jetty.webapp.WebAppContext
import org.slf4j.LoggerFactory

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
    // Disable browser cache
    val defaultServlet = new DefaultServlet
    val holder = new ServletHolder(defaultServlet)
    holder.setInitParameter("cacheControl", "no-store, no-cache")
    appContext.addServlet(holder, "/index.html")
    appContext.addServlet(new ServletHolder(new WMTSProxyServlet), "/wmts/maasto/*")
    appContext.addServlet(new ServletHolder(new WMTSProxyServlet), "/wmts/kiinteisto/*")
    appContext.getMimeTypes.addMimeMapping("ttf", "application/x-font-ttf")
    appContext.getMimeTypes.addMimeMapping("woff", "application/x-font-woff")
    appContext.getMimeTypes.addMimeMapping("eot", "application/vnd.ms-fontobject")
    appContext.getMimeTypes.addMimeMapping("js", "application/javascript; charset=UTF-8")
    appContext
  }

  class WMTSProxyServlet extends ProxyServlet {

    private val logger = LoggerFactory.getLogger(getClass)

    override def newHttpClient(): HttpClient = {
      new HttpClient(new SslContextFactory)
    }

    override def rewriteURI(req: HttpServletRequest): java.net.URI = {
      val url = ViiteProperties.rasterServiceURL + req.getRequestURI
      logger.debug(req.getRequestURI)
      logger.debug(url)
      java.net.URI.create(url)
    }

  }
}

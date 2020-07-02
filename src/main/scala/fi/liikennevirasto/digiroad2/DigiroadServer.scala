package fi.liikennevirasto.digiroad2

import java.lang.management.ManagementFactory

import fi.liikennevirasto.digiroad2.util.ViiteProperties
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}
import org.eclipse.jetty.client.api.Request
import org.eclipse.jetty.client.{HttpClient, HttpProxy}
import org.eclipse.jetty.jmx.MBeanContainer
import org.eclipse.jetty.proxy.ProxyServlet
import org.eclipse.jetty.server.handler.ContextHandlerCollection
import org.eclipse.jetty.server.{Handler, Server}
import org.eclipse.jetty.webapp.WebAppContext
import org.slf4j.LoggerFactory

import scala.collection.JavaConversions._

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
    appContext.addServlet(classOf[OAGProxyServlet], "/wmts/*")
    appContext.addServlet(classOf[ArcGisProxyServlet], "/arcgis/*")
    appContext.addServlet(classOf[OAGRasterServiceProxyServlet], "/rasteripalvelu/*")
    appContext.getMimeTypes.addMimeMapping("ttf", "application/x-font-ttf")
    appContext.getMimeTypes.addMimeMapping("woff", "application/x-font-woff")
    appContext.getMimeTypes.addMimeMapping("eot", "application/vnd.ms-fontobject")
    appContext.getMimeTypes.addMimeMapping("js", "application/javascript; charset=UTF-8")
    appContext
  }
}

class OAGProxyServlet extends ProxyServlet {

  override def rewriteURI(req: HttpServletRequest): java.net.URI = {
    val uri = req.getRequestURI
    java.net.URI.create(s"http://oag.liikennevirasto.fi/rasteripalvelu-mml$uri")
  }

  override def sendProxyRequest(clientRequest: HttpServletRequest, proxyResponse: HttpServletResponse, proxyRequest: Request): Unit = {
    super.sendProxyRequest(clientRequest, proxyResponse, proxyRequest)
  }
}

class OAGRasterServiceProxyServlet extends ProxyServlet {

  private val logger = LoggerFactory.getLogger(getClass)

  override def rewriteURI(req: HttpServletRequest): java.net.URI = {
    val uri = req.getRequestURI
    val url = s"http://oag.liikennevirasto.fi$uri?${req.getQueryString}"
    logger.info(url)
    java.net.URI.create(url)
  }

  override def sendProxyRequest(clientRequest: HttpServletRequest, proxyResponse: HttpServletResponse, proxyRequest: Request): Unit = {
    super.sendProxyRequest(clientRequest, proxyResponse, proxyRequest)
  }
}


class ArcGisProxyServlet extends ProxyServlet {
  private val logger = LoggerFactory.getLogger(getClass)

  override def rewriteURI(req: HttpServletRequest): java.net.URI = {
    val uri = req.getRequestURI
    val url = s"http://aineistot.esri.fi$uri"
    logger.info(url)
    java.net.URI.create(url)
  }

  override def sendProxyRequest(clientRequest: HttpServletRequest, proxyResponse: HttpServletResponse, proxyRequest: Request): Unit = {
    proxyRequest.header("Referer", null)
    proxyRequest.header("Host", null)
    proxyRequest.header("Cookie", null)
    proxyRequest.header("OAM_REMOTE_USER", null)
    proxyRequest.header("OAM_IDENTITY_DOMAIN", null)
    proxyRequest.header("OAM_LAST_REAUTHENTICATION_TIME", null)
    proxyRequest.header("OAM_GROUPS", null)
    proxyRequest.header("X-Forwarded-Host", null)
    proxyRequest.header("X-Forwarded-Server", null)
    proxyRequest.header("Via", null)
    super.sendProxyRequest(clientRequest, proxyResponse, proxyRequest)
  }

  override def getHttpClient: HttpClient = {
    val client = super.getHttpClient
    if (ViiteProperties.httpProxySet) {
      val proxy = new HttpProxy("127.0.0.1", 3128)
      proxy.getExcludedAddresses.addAll(ViiteProperties.httpNonProxyHosts.split("|").toList)
      client.getProxyConfiguration.getProxies.add(proxy)
      client.setIdleTimeout(60000)
    }
    client
  }
}

import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.authentication.SessionApi
import fi.liikennevirasto.digiroad2.util.ViiteProperties
import javax.servlet.ServletContext
import org.apache.http.client.config.{CookieSpecs, RequestConfig}
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.HttpClientBuilder
import org.scalatra._

import scala.io.Source
import scala.language.postfixOps

class ScalatraBootstrap extends LifeCycle {

  implicit val swagger: ViiteSwagger = new ViiteSwagger

  override def init(context: ServletContext) {
    context.mount(new SessionApi, "/api/auth/*")
    context.mount(new PingApi, "/api/ping/*")
    context.mount(new AdminApi(Digiroad2Context.dataImporter, swagger), "/api/admin/*")
    context.mount(new IntegrationApi(Digiroad2Context.roadAddressService, Digiroad2Context.roadNameService, swagger), "/api/viite/integration/*")
    context.mount(new ChangeApi(Digiroad2Context.roadAddressService, Digiroad2Context.nodesAndJunctionsService, swagger), "/api/viite/changes/*")
    context.mount(new SearchApi(Digiroad2Context.roadAddressService, swagger), "/api/viite/search/*")
    context.mount(new ViiteApi(Digiroad2Context.roadLinkService, Digiroad2Context.vvhClient, Digiroad2Context.roadAddressService, Digiroad2Context.projectService, Digiroad2Context.roadNetworkService, Digiroad2Context.roadNameService, Digiroad2Context.nodesAndJunctionsService, swagger = swagger), "/api/viite/*")
    context.mount(new ResourcesApp, "/api-docs")
    context.mount(new RasterProxy, "/rasteripalvelu")
    context.mount(new WmtsProxy, "/wmts")
  }
}

class RasterProxy extends ScalatraServlet {

  private val client = HttpClientBuilder.create()
    .setDefaultRequestConfig(RequestConfig.custom()
      .setCookieSpec(CookieSpecs.STANDARD).build()).build()

  get("/wmts/maasto") {
    val uriwithparams = "/wmts/maasto?service=" + params.get("service").get +"&request=" + params.get("request").get
    val proxyGet = new HttpGet(ViiteProperties.rasterServiceURL + uriwithparams)
    proxyGet.setHeader("X-API-Key", ViiteProperties.rasterServiceApiKey)
    proxyGet.removeHeaders("X-Iam-Data")
    proxyGet.removeHeaders("X-Iam-Accesstoken")
    proxyGet.removeHeaders("X-Amzn-Trace-Id")
    proxyGet.removeHeaders("X-Iam-Identity")
    val resp = client.execute(proxyGet)
    response.setStatus(resp.getStatusLine.getStatusCode)
    contentType = resp.getEntity.getContentType.getValue
    val data = Source.fromInputStream(resp.getEntity.getContent)
    data.mkString
  }
}

class WmtsProxy extends ScalatraServlet {

  private val client = HttpClientBuilder.create()
    .setDefaultRequestConfig(RequestConfig.custom()
    .setCookieSpec(CookieSpecs.STANDARD).build()).build()


  get("/*") {
    val uri = request.getRequestURI
    val proxyGet = new HttpGet(ViiteProperties.rasterServiceURL + uri)
    proxyGet.setHeader("X-API-Key", ViiteProperties.rasterServiceApiKey)
    proxyGet.removeHeaders("X-Iam-Data")
    proxyGet.removeHeaders("X-Iam-Accesstoken")
    proxyGet.removeHeaders("X-Amzn-Trace-Id")
    proxyGet.removeHeaders("X-Iam-Identity")
    val resp = client.execute(proxyGet)
    response.setStatus(resp.getStatusLine.getStatusCode)
    contentType = resp.getEntity.getContentType.getValue
    val data = resp.getEntity.getContent
    val out = response.getOutputStream
    val bytes = new Array[Byte](4096)
    Iterator
      .continually(data.read(bytes))
      .takeWhile(-1 !=)
      .foreach(read => out.write(bytes,0,read))
    out.close()
  }
}

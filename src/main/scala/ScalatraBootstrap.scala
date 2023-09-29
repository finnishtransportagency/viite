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
    context.mount(new DynamicRoadNetworkApi(Digiroad2Context.dynamicRoadNetworkService), "/api/roadnetwork/*")
    context.mount(new SessionApi, "/api/auth/*")
    context.mount(new PingApi, "/api/ping/*")
    context.mount(new AdminApi(Digiroad2Context.dataImporter, swagger), "/api/admin/*")
    context.mount(new IntegrationApi(Digiroad2Context.roadAddressService, Digiroad2Context.roadNameService, swagger), "/api/viite/integration/*")
    context.mount(new ChangeApi(Digiroad2Context.roadAddressService, Digiroad2Context.nodesAndJunctionsService, swagger), "/api/viite/changes/*")
    context.mount(new SearchApi(Digiroad2Context.roadAddressService, swagger), "/api/viite/search/*")
    context.mount(new ViiteApi(Digiroad2Context.roadLinkService, Digiroad2Context.kgvRoadLinkClient, Digiroad2Context.roadAddressService, Digiroad2Context.projectService, Digiroad2Context.roadNameService, Digiroad2Context.nodesAndJunctionsService, Digiroad2Context.roadNetworkValidator, swagger = swagger), "/api/viite/*")
    context.mount(new ResourcesApp, "/api-docs")
    context.mount(new RasterProxy, "/rasteripalvelu")
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

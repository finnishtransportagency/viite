import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.authentication.SessionApi
import fi.liikennevirasto.digiroad2.util.ViiteProperties
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.core5.http.ClassicHttpResponse
import org.apache.hc.core5.http.io.HttpClientResponseHandler
import org.scalatra._

import java.io.IOException
import javax.servlet.ServletContext
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
    context.mount(new ViiteApi(Digiroad2Context.roadLinkService,      Digiroad2Context.kgvRoadLinkClient,
                               Digiroad2Context.roadAddressService,   Digiroad2Context.projectService,
                               Digiroad2Context.roadNameService,      Digiroad2Context.nodesAndJunctionsService,
                               Digiroad2Context.roadNetworkValidator, Digiroad2Context.userProvider,
                               Digiroad2Context.deploy_date,          swagger),
                  "/api/viite/*")
    context.mount(new ResourcesApp, "/api-docs")
    context.mount(new RasterProxy, "/rasteripalvelu")
  }
}

class RasterProxy extends ScalatraServlet {

  private val client = HttpClients.createDefault()

  /** Create a response handler, with handleResponse implementation returning the triple
    * response code, contentType, and response data. */
  def getResponseHandler = {
    new HttpClientResponseHandler[(Int, String, String)] {
      @throws[IOException]
      override def handleResponse(response: ClassicHttpResponse): (Int, String, String)  = {
        val data = Source.fromInputStream(response.getEntity.getContent)

        (response.getCode, response.getEntity.getContentType, data.mkString)
      }
    }
  }

  get("/wmts/maasto") {
    val uriwithparams = "/wmts/maasto?service=" + params.get("service").get +"&request=" + params.get("request").get
    val proxyGet = new HttpGet(ViiteProperties.rasterServiceURL + uriwithparams)
    proxyGet.setHeader("X-API-Key", ViiteProperties.rasterServiceApiKey)
    proxyGet.removeHeaders("X-Iam-Data")
    proxyGet.removeHeaders("X-Iam-Accesstoken")
    proxyGet.removeHeaders("X-Amzn-Trace-Id")
    proxyGet.removeHeaders("X-Iam-Identity")
    val (httpStatusCode, httpContentType, resp) = client.execute(proxyGet, getResponseHandler)
    response.setStatus(httpStatusCode)
    contentType = httpContentType
    resp
  }
}

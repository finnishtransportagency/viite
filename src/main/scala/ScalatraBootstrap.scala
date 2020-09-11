import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.authentication.SessionApi
import fi.liikennevirasto.digiroad2.util.ViiteProperties
import org.scalatra._
import javax.servlet.ServletContext
import org.scalatra.swagger._


class ScalatraBootstrap extends LifeCycle {

  implicit val swagger: ViiteSwagger = new ViiteSwagger

  override def init(context: ServletContext) {
    context.mount(new SessionApi, "/api/auth/*")
    context.mount(new PingApi, "/api/ping/*")
    context.mount(new IntegrationApi(Digiroad2Context.roadAddressService, Digiroad2Context.roadNameService, swagger), "/api/viite/integration/*")
    context.mount(new ChangeApi(Digiroad2Context.roadAddressService, Digiroad2Context.nodesAndJunctionsService, swagger), "/api/viite/changes/*")
    context.mount(new SearchApi(Digiroad2Context.roadAddressService, swagger), "/api/viite/search/*")
    context.mount(new ViiteApi(Digiroad2Context.roadLinkService, Digiroad2Context.vvhClient,
      Digiroad2Context.roadAddressService, Digiroad2Context.projectService, Digiroad2Context.roadNetworkService,
      Digiroad2Context.roadNameService, Digiroad2Context.nodesAndJunctionsService, swagger = swagger), "/api/viite/*")
    if (ViiteProperties.tierekisteriEnabled) {
      val url = ViiteProperties.tierekisteriViiteRestApiEndPoint
      if ("http://localhost.*/api/trrest/".r.findFirstIn(url).nonEmpty) {
        println("Using local tierekisteri mock at /api/trrest/")
        context.mount(new ViiteTierekisteriMockApi(Digiroad2Context.projectService), "/api/trrest/*")
      } else {
        println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
        println("NOTE! Tierekisteri integration enabled but not using local mock")
        println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
      }
    } else {
      // Mount for manual testing purposes but do not use them
      context.mount(new ViiteTierekisteriMockApi(Digiroad2Context.projectService), "/api/trrest/*")
    }

    context.mount(new ResourcesApp, "/api-docs")
  }
}

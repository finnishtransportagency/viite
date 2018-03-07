import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.authentication.SessionApi
import fi.liikennevirasto.digiroad2.user.UserConfigurationApi
import org.scalatra._
import javax.servlet.ServletContext


class ScalatraBootstrap extends LifeCycle {
  override def init(context: ServletContext) {
    context.mount(new SessionApi, "/api/auth/*")
    context.mount(new UserConfigurationApi, "/api/userconfig/*")
    context.mount(new PingApi, "/api/ping/*")
    context.mount(new ViiteIntegrationApi(Digiroad2Context.roadAddressService), "/api/viite/integration/*")
    context.mount(new RoadAddressApi(Digiroad2Context.roadAddressService), "/api/viite/testing/*")
    context.mount(new ViiteApi(Digiroad2Context.roadLinkService, Digiroad2Context.vvhClient,
      Digiroad2Context.roadAddressService, Digiroad2Context.projectService, Digiroad2Context.roadNetworkService), "/api/viite/*")
    if (Digiroad2Context.getProperty("digiroad2.tierekisteri.enabled").toBoolean) {
      val url = Digiroad2Context.getProperty("digiroad2.tierekisteriViiteRestApiEndPoint")
      if ("http://localhost.*/api/trrest/".r.findFirstIn(url).nonEmpty) {
        println("Using local tierekisteri mock at /api/trrest/")
        context.mount(new ViiteTierekisteriMockApi, "/api/trrest/*")
      } else {
        println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
        println("NOTE! Tierekisteri integration enabled but not using local mock")
        println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
      }
    } else {
      // Mount for manual testing purposes but do not use them
      context.mount(new ViiteTierekisteriMockApi, "/api/trrest/*")
    }
  }
}

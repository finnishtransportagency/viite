package fi.liikennevirasto.digiroad2

import org.scalatra.ScalatraServlet
import org.scalatra.swagger.{ApiInfo, NativeSwaggerBase, Swagger}

class ResourcesApp(implicit val swagger: Swagger) extends ScalatraServlet with NativeSwaggerBase

object ViiteApiInfo extends ApiInfo(title = "VIITE API",
  description = "Docs for VIITE API",
  termsOfServiceUrl = "",
  contact = "",
  license = "",
  licenseUrl =""
)

class ViiteSwagger extends Swagger(Swagger.SpecVersion, "1.0.0", ViiteApiInfo)


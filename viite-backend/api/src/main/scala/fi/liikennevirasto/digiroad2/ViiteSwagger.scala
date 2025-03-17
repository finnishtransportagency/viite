package fi.liikennevirasto.digiroad2

import org.scalatra.ScalatraServlet
import org.scalatra.swagger.{ApiInfo, ContactInfo, LicenseInfo, NativeSwaggerBase, Swagger}

class ResourcesApp(implicit val swagger: Swagger) extends ScalatraServlet with NativeSwaggerBase

object ViiteApiInfo extends ApiInfo(title = "VIITE API",
  description = "Docs for VIITE API",
  termsOfServiceUrl = "",
  contact = ContactInfo(name = "", url = "", email = ""),  // Empty but valid ContactInfo
  license = LicenseInfo(name = "", url = "")  // Empty but valid LicenseInfo
)

class ViiteSwagger extends Swagger(Swagger.SpecVersion, "1.0.0", ViiteApiInfo)


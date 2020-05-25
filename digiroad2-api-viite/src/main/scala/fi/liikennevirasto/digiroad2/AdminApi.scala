package fi.liikennevirasto.digiroad2

import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.util.DatabaseMigration
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite.util.DataImporter
import org.json4s.{DefaultFormats, Formats}
import org.scalatra.ScalatraBase
import org.scalatra.auth.strategy.BasicAuthSupport
import org.scalatra.auth.{ScentryConfig, ScentrySupport}
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{Swagger, SwaggerSupport}
import org.slf4j.{Logger, LoggerFactory}

trait AdminAuthenticationSupport extends ScentrySupport[BasicAuthUser] with BasicAuthSupport[BasicAuthUser] {
  self: ScalatraBase =>

  val realm = "Viite Admin API"

  protected def fromSession: PartialFunction[String, BasicAuthUser] = {
    case id: String => BasicAuthUser(id)
  }

  protected def toSession: PartialFunction[BasicAuthUser, String] = {
    case user: BasicAuthUser => user.username
  }

  protected val scentryConfig: ScentryConfiguration = new ScentryConfig {}.asInstanceOf[ScentryConfiguration]

  override protected def configureScentry: Unit = {
    scentry.unauthenticated {
      scentry.strategies("Basic").unauthenticated()
    }
  }

  override protected def registerAuthStrategies: Unit = {
    scentry.register("Basic", app => new IntegrationAuthStrategy(app, realm, "admin"))
  }
}

import org.scalatra.ScalatraServlet

class AdminApi(val dataImporter: DataImporter, implicit val swagger: Swagger) extends ScalatraServlet
  with JacksonJsonSupport with AdminAuthenticationSupport with SwaggerSupport {

  val logger: Logger = LoggerFactory.getLogger(getClass)

  protected val applicationDescription = "The Admin API "

  protected implicit val jsonFormats: Formats = DefaultFormats

  case class AssetTimeStamps(created: Modification, modified: Modification) extends TimeStamps

  before() {
    basicAuth
  }

  get("/import_road_addresses") {
    time(logger, "GET request for /import_road_addresses") {
      val conversionTable = params.get("conversion_table")
      dataImporter.importRoadAddresses(conversionTable)
    }
  }

  get("/update_road_addresses_geometry") {
    time(logger, "GET request for /update_road_addresses_geometry") {
      dataImporter.updateLinearLocationGeometry()
    }
  }

  get("/flyway_init") {
    time(logger, "GET request for /flyway_init") {
      DatabaseMigration.flywayInit
    }
  }

  get("/flyway_migrate") {
    time(logger, "GET request for /flyway_migrate") {
      DatabaseMigration.migrate
    }
  }

  get("/import_nodes_and_junctions") {
    time(logger, "GET request for /import_nodes_and_junctions") {
      dataImporter.importNodesAndJunctions()
    }
  }

}

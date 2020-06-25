package fi.liikennevirasto.digiroad2

import java.net.URLDecoder

import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.util.DatabaseMigration
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite.util.DataImporter
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.HttpClientBuilder
import org.json4s.{DefaultFormats, Formats}
import org.scalatra.auth.strategy.BasicAuthSupport
import org.scalatra.auth.{ScentryConfig, ScentrySupport}
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{Swagger, SwaggerSupport}
import org.scalatra._
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

class AdminApi(val dataImporter: DataImporter, implicit val swagger: Swagger) extends ScalatraServlet
  with JacksonJsonSupport with AdminAuthenticationSupport with SwaggerSupport {

  val logger: Logger = LoggerFactory.getLogger(getClass)

  protected val applicationDescription = "The Admin API "

  protected implicit val jsonFormats: Formats = DefaultFormats

  case class AssetTimeStamps(created: Modification, modified: Modification) extends TimeStamps

  before() {
    basicAuth
  }

  get("/initial_import") {
    val conversionTable = params.get("conversion_table")
    time(logger, "GET request for /initial_import") {
      try {
        dataImporter.initialImport(conversionTable)
        Ok("Initial import successful.\n")
      } catch {
        case e: Exception => {
          logger.error("Initial import failed.", e)
          InternalServerError(s"Initial import failed: ${e.getMessage}")
        }
      }
    }
  }

  get("/import_road_addresses") {
    val conversionTable = params.get("conversion_table")
    time(logger, "GET request for /import_road_addresses") {
      try {
        dataImporter.importRoadAddresses(conversionTable)
        Ok("Importing road addresses successful.\n")
      } catch {
        case e: Exception => {
          logger.error("Importing road addresses failed.", e)
          InternalServerError(s"Importing road addresses failed: ${e.getMessage}")
        }
      }
    }
  }

  get("/import_road_names") {
    time(logger, "GET request for /import_road_names") {
      try {
        dataImporter.importRoadNames()
        Ok("Importing road names successful.\n")
      } catch {
        case e: Exception => {
          logger.error("Importing road names failed.", e)
          InternalServerError(s"Importing road names failed: ${e.getMessage}")
        }
      }
    }
  }

  get("/test_get_request") {
    val urlParam = params.get("url")
    time(logger, "GET request for /test_get_request") {
      try {
        if (urlParam.isEmpty) {
          logger.info("Parameter 'url' was not given.")
          BadRequest("Parameter 'url' is required.")
        } else {
          val url = urlParam.get
          logger.info(s"Testing connection to url: $url")
          val request = new HttpGet(url)
          val client = HttpClientBuilder.create().build
          val response = client.execute(request)
          val statusCode = response.getStatusLine.getStatusCode
          Ok(s"Response status: $statusCode from url: $url\n")
        }
      } catch {
        case e: Exception => {
          logger.error("Test connection failed.", e)
          InternalServerError(s"Test connection failed: ${e.getMessage}")
        }
      }
    }
  }

  get("/import_municipalities") {
    time(logger, "GET request for /import_municipalities") {
      try {
        dataImporter.importMunicipalities()
        Ok("Importing municipalities successful.\n")
      } catch {
        case e: Exception => {
          logger.error("Importing municipalities failed.", e)
          InternalServerError(s"Importing municipalities failed: ${e.getMessage}")
        }
      }
    }
  }

  get("/update_road_addresses_geometry") {
    time(logger, "GET request for /update_road_addresses_geometry") {
      try {
        dataImporter.updateLinearLocationGeometry()
        Ok("Updating linear location geometry successful.\n")
      } catch {
        case e: Exception => {
          logger.error("Updating linear location geometry failed.", e)
          InternalServerError(s"Updating linear location geometry failed: ${e.getMessage}")
        }
      }
    }
  }

  get("/flyway_init") {
    logger.info("GET request for /flyway_init...")
    time(logger, "GET request for /flyway_init") {
      try {
        DatabaseMigration.flywayInit
        logger.info("Flyway init successful.")
        Ok("Flyway init successful.\n")
      } catch {
        case e: Exception => {
          logger.error("Flyway init failed.", e)
          InternalServerError(s"Flyway init failed: ${e.getMessage}")
        }
      }
    }
  }

  get("/flyway_migrate") {
    time(logger, "GET request for /flyway_migrate") {
      try {
        DatabaseMigration.migrate
        logger.info("Flyway migrate successful.")
        Ok("Flyway migrate successful.\n")
      } catch {
        case e: Exception => {
          logger.error("Flyway migrate failed.", e)
          InternalServerError(s"Flyway migrate failed: ${e.getMessage}")
        }
      }
    }
  }

  get("/import_nodes_and_junctions") {
    time(logger, "GET request for /import_nodes_and_junctions") {
      try {
        dataImporter.importNodesAndJunctions()
        Ok("Import nodes and junctions successful.\n")
      } catch {
        case e: Exception => {
          logger.error("Import nodes and junctions failed.", e)
          InternalServerError(s"Import nodes and junctions: ${e.getMessage}")
        }
      }
    }
  }

}

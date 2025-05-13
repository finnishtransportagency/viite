package fi.liikennevirasto.digiroad2

import fi.liikennevirasto.digiroad2.Digiroad2Context.dynamicRoadNetworkService
import fi.liikennevirasto.digiroad2.util.DatabaseMigration
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite.util.DataImporter
import fi.vaylavirasto.viite.util.DateTimeFormatters.ISOdateFormatter
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.core5.http.ClassicHttpResponse
import org.apache.hc.core5.http.io.HttpClientResponseHandler
import org.joda.time.DateTime
import org.json4s.{DefaultFormats, Formats, StringInput}
import org.scalatra._
import org.scalatra.auth.{ScentryConfig, ScentrySupport}
import org.scalatra.auth.strategy.BasicAuthSupport
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{Swagger, SwaggerSupport}
import org.slf4j.{Logger, LoggerFactory}

import java.io.IOException
import java.net.URLDecoder
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

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
        case e: Exception =>
          logger.error("Initial import failed.", e)
          InternalServerError(s"Initial import failed: ${e.getMessage}")
      }
    }
  }

  /** Runs partial initial import, that basically consists of
    * /import_road_addresses (importRoadAddresses), and
    * /update_geometry (updateSplittedLinkLinearLocationGeometries).
    * importRoadAddresses wipes off the db data, gets the new data from drkonv, and processes it into Viite.
    * updateSplittedLinkLinearLocationGeometries sets the linear location geometries
    * correctly into the splitted links that consist of multiple linear locations.
    * @param conversion_table table name to read the conversion data from.
    */
  get("/partial_initial_import") {
    val conversionTable = params.get("conversion_table")

    time(logger, "GET request for /partial_initial_import") {

      var phase = "(Not started yet)"

      try {
        // First, import the changed (samuutettu) import data
        phase = "Importing road addresses"
        logger.info(s"partial_initial_import: $phase starting.")
        dataImporter.importRoadAddresses(conversionTable)
        logger.info(s"partial_initial_import: $phase successful.")

        // Then, update the splitted linear locations so that they have correctly splitted geometries
        phase = "Updating splitted link geometries"
        logger.info(s"partial_initial_import: $phase starting.")
        dataImporter.updateLinearLocationGeometry()
        logger.info(s"partial_initial_import: $phase successful.")

        // Return http-OK, when everything passed without Exceptions.
        Ok("partial_initial_import successful.\n")

      } catch {
        // In case of an exception, tell the failing phase for the caller. More details into the log.
        case e: Exception =>
          logger.error(s"partial_initial_import: $phase failed.", e)
          InternalServerError(s"partial_initial_import failed: $phase")
      }

    }
  }

  get("/update_geometry") {
    time(logger, "GET request for /update_geometry") {
      try {
        dataImporter.updateLinearLocationGeometry()
        Ok("Geometry update successful.\n")
      } catch {
        case e: Exception =>
          logger.error("Geometry update failed.", e)
          InternalServerError(s"Geometry update failed: ${e.getMessage}")
      }
    }
  }

  get("/import_nodes_and_junctions") {
    time(logger, "GET request for /import_nodes_and_junctions") {
      try {
        dataImporter.importNodesAndJunctions()
        Ok("Importing nodes and junctions successful.\n")
      } catch {
        case e: Exception =>
          logger.error("Importing nodes and junctions failed.", e)
          InternalServerError(s"Importing nodes and junctions failed: ${e.getMessage}")
      }
    }
  }

  get("/update_calibration_point_types") {
    time(logger, "GET request for /update_calibration_point_types") {
      try {
        dataImporter.updateCalibrationPointTypes()
        Ok("Updating calibration point types successful.\n")
      } catch {
        case e: Exception =>
          logger.error("Updating calibration point types failed.", e)
          InternalServerError(s"Updating calibration point types failed: ${e.getMessage}")
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
        case e: Exception =>
          logger.error("Importing road addresses failed.", e)
          InternalServerError(s"Importing road addresses failed: ${e.getMessage}")
      }
    }
  }

  get("/import_road_names") {
    time(logger, "GET request for /import_road_names") {
      try {
        dataImporter.importRoadNames()
        Ok("Importing road names successful.\n")
      } catch {
        case e: Exception =>
          logger.error("Importing road names failed.", e)
          InternalServerError(s"Importing road names failed: ${e.getMessage}")
      }
    }
  }

  get("/test_get_request") {
    val urlParam = params.get("url")
    val headersParam = params.get("headers")

    /** Create a response handler, with handleResponse implementation returning the http response status code. */
    def getResponseHandler(url: String) = {
      new HttpClientResponseHandler[Int] {
        @throws[IOException]
        override def handleResponse(response: ClassicHttpResponse): Int  = {
          response.getCode
        }
      }
    }

    time(logger, "GET request for /test_get_request") {
      try {
        if (urlParam.isEmpty) {
          logger.info("Parameter 'url' was not given.")
          BadRequest("Parameter 'url' is required.")
        } else {
          val url = urlParam.get
          logger.info(s"Testing connection to url: $url")
          val request = new HttpGet(url)
          if (headersParam.isDefined) {
            val headers = parse(StringInput(URLDecoder.decode(headersParam.get))).values.asInstanceOf[Map[String, String]]
            headers.foreach { case (name, value) => request.addHeader(name, value) }
          }
          val client = HttpClients.createDefault()
          val statusCode = client.execute(request, getResponseHandler(url))
          Ok(s"Response status: $statusCode from url: $url\n")
        }
      } catch {
        case e: Exception =>
          logger.error("Test connection failed.", e)
          InternalServerError(s"Test connection failed: ${e.getMessage}")
      }
    }
  }

  get("/import_municipalities") {
    time(logger, "GET request for /import_municipalities") {
      try {
        dataImporter.importMunicipalities()
        Ok("Importing municipalities successful.\n")
      } catch {
        case e: Exception =>
          logger.error("Importing municipalities failed.", e)
          InternalServerError(s"Importing municipalities failed: ${e.getMessage}")
      }
    }
  }

  get("/update_road_addresses_geometry") {
    time(logger, "GET request for /update_road_addresses_geometry") {
      try {
        dataImporter.updateLinearLocationGeometry()
        Ok("Updating linear location geometry successful.\n")
      } catch {
        case e: Exception =>
          logger.error("Updating linear location geometry failed.", e)
          InternalServerError(s"Updating linear location geometry failed: ${e.getMessage}")
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
        case e: Exception =>
          logger.error("Flyway init failed.", e)
          InternalServerError(s"Flyway init failed: ${e.getMessage}")
      }
    }
  }

  get("/flyway_migrate") {
    time(logger, "GET request for /flyway_migrate") {
      try {
        val outOfOrder = params.get("out_of_order") match {
          case Some(outOfOrderParam) => outOfOrderParam.equalsIgnoreCase("true")
          case _ => false
        }
        DatabaseMigration.migrate(outOfOrder)
        logger.info("Flyway migrate successful.")
        Ok("Flyway migrate successful.\n")
      } catch {
        case e: Exception =>
          logger.error("Flyway migrate failed.", e)
          InternalServerError(s"Flyway migrate failed: ${e.getMessage}")
      }
    }
  }

  get("/import_nodes_and_junctions") {
    time(logger, "GET request for /import_nodes_and_junctions") {
      try {
        dataImporter.importNodesAndJunctions()
        Ok("Import nodes and junctions successful.\n")
      } catch {
        case e: Exception =>
          logger.error("Import nodes and junctions failed.", e)
          InternalServerError(s"Import nodes and junctions: ${e.getMessage}")
      }
    }
  }

  def validateDateParams(previousDate: String, newDate: String): (DateTime, DateTime) = {
    val format = ISOdateFormatter
    (format.parseDateTime(previousDate), format.parseDateTime(newDate))
  }

  def handleUpdate(previousDate: String, newDate: String, processDaily: Boolean): ActionResult = {
    try {
      val (previousDateTimeObject, newDateTimeObject) = validateDateParams(previousDate, newDate)
      Future(dynamicRoadNetworkService.initiateLinkNetworkUpdates(previousDateTimeObject, newDateTimeObject, processDaily))
      Ok("Samuutus käynnistetty")
    } catch {
      case ex: IllegalArgumentException =>
        logger.error("Updating link network failed.", ex)
        BadRequest("Unable to parse date, the date should be in yyyy-MM-dd format.")
      case e: Exception =>
        logger.error("Updating link network failed.", e)
        InternalServerError(s"Updating link network failed: ${e.getMessage}")
    }
  }

  /**
   * Part of dynamic link network.
   * End point to initiate link network update from @previousDate to @newDate
   * @param previousDate (YYYY-MM-DD) Previous road link network version date (usually the road link network version that Viite is using at the moment)
   * @param newDate (YYYY-MM-DD) The date to which the road link network (version) will be updated to.
   */
  get("/update_link_network") {
    time(logger, "GET request for /update_link_network") {
      val previousDate = params.get("previousDate")
      val newDate = params.get("newDate")

      (previousDate, newDate) match {
        case (Some(previousDate), Some(newDate)) =>
          params.get("processDaily") match {
            case Some("true")  => handleUpdate(previousDate, newDate, processDaily = true)
            case Some("false") => handleUpdate(previousDate, newDate, processDaily = false)
            case Some(invalid) =>
              logger.error(s"Invalid boolean value for 'processDaily': $invalid")
              BadRequest(s"Invalid boolean value for 'processDaily': $invalid")
            case None => handleUpdate(previousDate, newDate, processDaily = false)
          }

        case _ =>
          BadRequest("Missing mandatory date parameter from the url - for example: /update_link_network?previousDate=2023-05-20&newDate=2023-05-23")
      }
    }
  }

}

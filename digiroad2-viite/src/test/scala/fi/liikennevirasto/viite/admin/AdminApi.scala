package fi.liikennevirasto.viite.admin

import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.util.DatabaseMigration
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite.util.DataFixture
import fi.liikennevirasto.viite.{RoadAddressService, RoadNameService}
import org.json4s.{DefaultFormats, Formats}
import org.scalatra.ScalatraBase
import org.scalatra.auth.strategy.BasicAuthSupport
import org.scalatra.auth.{ScentryConfig, ScentrySupport}
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{Swagger, SwaggerSupport}
import org.slf4j.{Logger, LoggerFactory}

trait ViiteAdminAuthenticationSupport extends ScentrySupport[BasicAuthUser] with BasicAuthSupport[BasicAuthUser] {
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
    scentry.register("Basic", app => new AdminAuthStrategy(app, realm, "admin"))
  }
}

import org.scalatra.ScalatraServlet

class AdminApi(val roadAddressService: RoadAddressService, val roadNameService: RoadNameService, implicit val swagger: Swagger) extends ScalatraServlet
  with JacksonJsonSupport with ViiteAdminAuthenticationSupport with SwaggerSupport {

  val logger: Logger = LoggerFactory.getLogger(getClass)

  protected val applicationDescription = "The Admin API "

  protected implicit val jsonFormats: Formats = DefaultFormats

  case class AssetTimeStamps(created: Modification, modified: Modification) extends TimeStamps

  before() {
    basicAuth
  }

  get("/admin/check_road_network") {
    time(logger, "GET request for /admin/check_road_network") {
      DataFixture.checkRoadNetwork()
    }
  }

  get("/admin/import_road_addresses") {
    time(logger, "GET request for /admin/import_road_addresses") {
      val conversionTable = params.get("conversion_table")
      DataFixture.importRoadAddresses(conversionTable)
    }
  }

  get("/admin/import_complementary_road_address") {
    time(logger, "GET request for /admin/import_complementary_road_address") {
      DataFixture.importComplementaryRoadAddress()
    }
  }

  get("/admin/update_road_addresses_geometry") {
    time(logger, "GET request for /admin/update_road_addresses_geometry") {
      DataFixture.updateLinearLocationGeometry()
    }
  }

  get("/admin/import_road_address_change_test_data") {
    time(logger, "GET request for /admin/import_road_address_change_test_data") {
      DataFixture.importRoadAddressChangeTestData()
    }
  }

  get("/admin/apply_change_information_to_road_address_links") {
    time(logger, "GET request for /admin/apply_change_information_to_road_address_links") {
      val numThreads = params.getAs[Int]("threads").getOrElse(1)
      DataFixture.applyChangeInformationToRoadAddressLinks(numThreads)
    }
  }

  get("/admin/import_road_names") {
    time(logger, "GET request for /admin/import_road_names") {
      DataFixture.importRoadNames()
    }
  }

  get("/admin/flyway_init") {
    time(logger, "GET request for /admin/flyway_init") {
      DataFixture.flywayInit()
    }
  }

  get("/admin/flyway_migrate") {
    time(logger, "GET request for /admin/flyway_migrate") {
      DatabaseMigration.migrate
    }
  }

  get("/admin/import_nodes_and_junctions") {
    time(logger, "GET request for /admin/import_nodes_and_junctions") {
      DataFixture.importNodesAndJunctions()
    }
  }

}

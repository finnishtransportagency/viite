package fi.liikennevirasto.viite.util

import org.flywaydb.core.Flyway
import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.client.kgv.KgvRoadLink
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.{SqlScriptRunner, ViiteProperties}
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.process.{ApplyChangeInfoProcess, RoadwayAddressMapper}
import fi.vaylavirasto.viite.dao.MunicipalityDAO
import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC
import org.flywaydb.core.api.configuration.FluentConfiguration
import org.joda.time.DateTime
import scalikejdbc.ConnectionPool

import javax.sql.DataSource
import scala.collection.parallel.ForkJoinTaskSupport
import scala.collection.parallel.immutable.ParSet
import scala.language.postfixOps

object DataFixture {

  val dataImporter = new DataImporter
  lazy val KGVClient: KgvRoadLink = {
    new KgvRoadLink
  }

  private lazy val geometryFrozen: Boolean = ViiteProperties.kgvRoadlinkFrozen

  val eventBus = new DummyEventBus
  val linkService = new RoadLinkService(KGVClient, eventBus, new DummySerializer, geometryFrozen)
  val roadAddressDAO = new RoadwayDAO
  val linearLocationDAO = new LinearLocationDAO
  val roadNetworkDAO: RoadNetworkDAO = new RoadNetworkDAO
  val roadwayPointDAO = new RoadwayPointDAO
  val nodePointDAO = new NodePointDAO
  val junctionPointDAO = new JunctionPointDAO
  val roadAddressService = new RoadAddressService(linkService, roadAddressDAO, linearLocationDAO, roadNetworkDAO, roadwayPointDAO, nodePointDAO, junctionPointDAO, new RoadwayAddressMapper(roadAddressDAO, linearLocationDAO), eventBus, ViiteProperties.kgvRoadlinkFrozen)

  private lazy val numberThreads: Int = 6

  private def toIntNumber(value: Any): Int = {
    try {
      value match {
        case b: Int => b.intValue()
        case _ => value.asInstanceOf[String].toInt
      }
    } catch {
      case _: Exception => numberThreads
    }
  }

  def importRoadAddresses(importTableName: Option[String]): Unit = {
    dataImporter.importRoadAddresses(importTableName)
  }

  def importNodesAndJunctions(): Unit = {
    dataImporter.importNodesAndJunctions()
  }

  def updateCalibrationPointTypes(): Unit = {
    dataImporter.updateCalibrationPointTypes()
  }

  def initialImport(importTableName: Option[String]): Unit = {
    dataImporter.initialImport(importTableName)
  }

  def updateLinearLocationGeometry(): Unit = {
    dataImporter.updateLinearLocationGeometry()
  }

  def importComplementaryRoadAddress(): Unit = {
    println(s"\nCommencing complementary road address import at time: ${DateTime.now()}")
    SqlScriptRunner.runViiteScripts(List(
      "insert_complementary_geometry_data.sql"
    ))
    println(s"complementary road address import completed at time: ${DateTime.now()}")
    println()
  }

  def importRoadNames(): Unit = {
    dataImporter.importRoadNames()
  }

  def importRoadAddressChangeTestData(): Unit = {
    println(s"\nCommencing road address change test data import at time: ${DateTime.now()}")
    SqlScriptRunner.runViiteScripts(List(
      "insert_road_address_change_test_data.sql"
    ))
    println(s"Road Address Change Test Data import completed at time: ${DateTime.now()}")
    println()
  }

  private def testIntegrationAPIWithAllMunicipalities(): Unit = {
    println(s"\nStarting fetch for integration API for all municipalities")
    val municipalities = PostGISDatabaseScalikeJDBC.runWithReadOnlySession {
      MunicipalityDAO.fetchMunicipalityIds
    }
    val failedMunicipalities = municipalities.map(
      municipalityCode =>
        try {
          println(s"\nProcessing municipality $municipalityCode")
          val knownAddressLinksSize = roadAddressService.getAllByMunicipality(municipalityCode)
          if (knownAddressLinksSize.nonEmpty) {
            println(s"\nMunicipality $municipalityCode returned ${knownAddressLinksSize.size} links with valid values")
            None
          } else {
            println(s"\n*** WARNING Municipality $municipalityCode returned zero links! ***")
            municipalityCode
          }
        }
        catch {
          case e: Exception =>
            val message = s"\n*** ERROR Failed to get road addresses for municipality $municipalityCode! ***"
            println(s"\n" + message + s"\n" + e.printStackTrace())
            municipalityCode
        }
    ).filterNot(_ == None)
    println(s"\n------------------------------------------------------------------------------\n")
    if (failedMunicipalities.nonEmpty) {
      println(s"*** ${failedMunicipalities.size} municipalities returned 0 links. Those municipalities are: ${failedMunicipalities.mkString(", ")} ***")
    } else {
      println(s"Test passed.")
    }
  }

  val flyway: Flyway = {
    PostGISDatabaseScalikeJDBC // Initialize the Database connection
    val ds: DataSource = ConnectionPool.get().dataSource // Get the DataSource from the ConnectionPool
    val flywayConf: FluentConfiguration = Flyway.configure
    flywayConf.dataSource(ds)
    flywayConf.locations("db/migration")
    flywayConf.baselineVersion("-1")

    val flyway = flywayConf.load()
    flyway
  }

  def migrateAll(): Int = {
    flyway.migrate().migrationsExecuted
  }

  def repair(): Unit = {
    flyway.repair()
  }

  def tearDown(): Unit = {
    // flyway.clean()
    // This old version of Flyway tries to drop the postgis extension too, so we clean the database manually instead
    SqlScriptRunner.runScriptInClasspath("/clear-db.sql")
    try {
      SqlScriptRunner.executeStatement("delete from flyway_schema_history where installed_rank > 1")
    } catch {
      case e: Exception => println(s"Failed to reset flyway_schema_history table: ${e.getMessage}")
    }

  }

  def setUpTest(): Unit = {
    migrateAll()
    SqlScriptRunner.runScripts(List(
      "insert_users.sql",
      "test_fixture_sequences.sql",
      "insert_roadways.sql",
      "insert_links.sql",
      "insert_roadway_points.sql",
      "insert_calibration_points.sql",
      "insert_linear_locations.sql",
      "insert_project_link_data.sql",
      "insert_road_names.sql",
      "municipalities.sql"
    ))
  }

  def flywayInit(): Unit = {
    flyway.baseline()
  }

  def main(args: Array[String]): Unit = {
    import scala.util.control.Breaks._
    val operation = args.headOption
    val username = ViiteProperties.conversionDbUsername
    if (!"Local".equalsIgnoreCase(ViiteProperties.env) && !operation.getOrElse("").equals("test_integration_api_all_municipalities")) {
      println("*************************************************************************************")
      println("YOU ARE RUNNING FIXTURE RESET AGAINST A NON-DEVELOPER DATABASE, TYPE 'YES' TO PROCEED")
      println("*************************************************************************************")
      breakable {
        while (true) {
          val input = scala.io.StdIn.readLine()
          if (input.trim() == "YES") {
            break()
          }
        }
      }
    }

    operation match {
      case Some("flyway_migrate") =>
        migrateAll()
      case Some("flyway_repair") =>
        repair()
      case Some("import_road_addresses") =>
        if (args.length > 1)
          importRoadAddresses(Some(args(1)))
        else
          throw new Exception("****** Import failed! conversiontable name required as second input ******")
      case Some("import_complementary_road_address") =>
        importComplementaryRoadAddress()
      case Some("update_road_addresses_geometry") =>
        updateLinearLocationGeometry()
      case Some("import_road_address_change_test_data") =>
        importRoadAddressChangeTestData()
      case Some("import_road_names") =>
        importRoadNames()
      case Some("test") =>
        tearDown()
        setUpTest()
      case Some("flyway_init") =>
        flywayInit()
      case Some("test_integration_api_all_municipalities") =>
        testIntegrationAPIWithAllMunicipalities()
      case Some("import_nodes_and_junctions") =>
        importNodesAndJunctions()
      case Some("initial_import") =>
        if (args.length > 1)
          initialImport(Some(args(1)))
        else
          throw new Exception("****** Import failed! conversiontable name required as second input ******")
      case Some("update_calibration_point_types") =>
        updateCalibrationPointTypes()
      case _ => println("Usage: DataFixture import_road_addresses <conversion table name> | update_missing " +
        "| import_complementary_road_address " +
        "| update_road_addresses_geometry | import_road_address_change_test_data " +
        "| apply_change_information_to_road_address_links | import_road_names | check_road_network" +
        "| test | flyway_init | flyway_migrate | import_nodes_and_junctions | initial_import | update_calibration_point_types")
    }
  }

  case class TimeLine(addressLength: Long, addresses: Seq[RoadAddress])

}

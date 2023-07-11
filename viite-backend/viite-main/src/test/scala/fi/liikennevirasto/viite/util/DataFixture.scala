package fi.liikennevirasto.viite.util

import java.util.Properties
import com.googlecode.flyway.core.Flyway
import com.jolbox.bonecp.{BoneCPConfig, BoneCPDataSource}
import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.client.kgv.KgvRoadLink
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase.ds
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.{SqlScriptRunner, ViiteProperties}
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.process.{ApplyChangeInfoProcess, ContinuityChecker, RoadwayAddressMapper}
import fi.liikennevirasto.viite.util.DataImporter.Conversion
import fi.vaylavirasto.viite.dao.{MunicipalityDAO, Queries}
import org.joda.time.DateTime

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

  lazy val continuityChecker = new ContinuityChecker(new RoadLinkService(KGVClient, new DummyEventBus, new DummySerializer, geometryFrozen))

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
    dataImporter.importNodesAndJunctions(Conversion.database())
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
    PostGISDatabase.withDynTransaction {
      PostGISDatabase.setSessionLanguage()
    }
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
    PostGISDatabase.withDynTransaction {
      PostGISDatabase.setSessionLanguage()
    }
    SqlScriptRunner.runViiteScripts(List(
      "insert_road_address_change_test_data.sql"
    ))
    println(s"Road Address Change Test Data import completed at time: ${DateTime.now()}")
    println()
  }

  private def testIntegrationAPIWithAllMunicipalities(): Unit = {
    println(s"\nStarting fetch for integration API for all municipalities")
    val municipalities = PostGISDatabase.withDynTransaction {
      Queries.getMunicipalities
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

  def applyChangeInformationToRoadAddressLinks(numThreads: Int): Unit = {

    val roadLinkService = new RoadLinkService(KGVClient, new DummyEventBus, new JsonSerializer, geometryFrozen)
    val linearLocationDAO = new LinearLocationDAO

    val linearLocations =
      PostGISDatabase.withDynTransaction {
        linearLocationDAO.fetchCurrentLinearLocations
      }

    println("Total linearLocations " + linearLocations.size)

    //get All municipalities and group them for ely
    PostGISDatabase.withDynTransaction {
      MunicipalityDAO.getMunicipalityMapping
    }.groupBy(_._2).foreach {
      case (ely, municipalityEly) =>

        //Get All Municipalities
        val municipalities: ParSet[Long] = municipalityEly.keySet.par
        println("Total municipalities keys for ely " + ely + " -> " + municipalities.size)

        //For each municipality get all Roadlinks
        municipalities.tasksupport = new ForkJoinTaskSupport(new scala.concurrent.forkjoin.ForkJoinPool(numThreads))
        municipalities.foreach { municipality =>
          println("Start processing municipality %d".format(municipality))

          //Obtain all RoadLink by municipality and change info from VVH
          val (roadLinks, changedRoadLinks) = roadLinkService.getRoadLinksAndChangesFromVVH(municipality.toInt)
          val allRoadLinks = roadLinks

          println("Total roadlinks for municipality " + municipality + " -> " + allRoadLinks.size)
          println("Total of changes for municipality " + municipality + " -> " + changedRoadLinks.size)
          if (roadLinks.nonEmpty) {
            try {

              val roadsChanges = ApplyChangeInfoProcess.applyChanges(linearLocations, allRoadLinks, changedRoadLinks)
              roadAddressService.updateChangeSet(roadsChanges._2)
              println(s"AppliedChanges for municipality $municipality")
              println(s"${roadsChanges._2.droppedSegmentIds.size} dropped roads")
              println(s"${roadsChanges._2.adjustedMValues.size} adjusted m values")
              println(s"${roadsChanges._2.newLinearLocations.size} new linear locations")
            } catch {
              case e: Exception => println("ERR! -> " + e.getMessage)
            }
          }
          println("End processing municipality %d".format(municipality))
        }
    }
  }

  /*private def showFreezeInfo(): Unit = {
    println("Road link geometry freeze is active; exiting without changes")
  }*/

  val flyway: Flyway = {
    val flyway = new Flyway()
    flyway.setDataSource(ds)
    flyway.setLocations("db.migration")
    flyway.setInitVersion("-1")
    flyway
  }

  def migrateAll(): Int = {
    flyway.migrate()
  }

  def repair(): Unit = {
    flyway.repair()
  }

  def tearDown(): Unit = {
    // flyway.clean()
    // This old version of Flyway tries to drop the postgis extension too, so we clean the database manually instead
    SqlScriptRunner.runScriptInClasspath("/clear-db.sql")
    try {
      SqlScriptRunner.executeStatement("delete from schema_version where version_rank > 1")
    } catch {
      case e: Exception => println(s"Failed to reset schema_version table: ${e.getMessage}")
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

  lazy val postgresDs: BoneCPDataSource = {
    Class.forName("org.postgresql.Driver")
    val props = new Properties()
    props.setProperty("bonecp.jdbcUrl", ViiteProperties.bonecpJdbcUrl)
    props.setProperty("bonecp.username", "postgres")
    props.setProperty("bonecp.password", "postgres")

    val cfg = new BoneCPConfig(props)
    new BoneCPDataSource(cfg)
  }

  def flywayInit(): Unit = {
    flyway.init()
  }

  def main(args: Array[String]): Unit = {
    import scala.util.control.Breaks._
    val operation = args.headOption
    val username = ViiteProperties.bonecpUsername
    if (!"Local".equalsIgnoreCase(ViiteProperties.env) && !operation.getOrElse("").equals("test_integration_api_all_municipalities")) {
      println("*************************************************************************************")
      println("YOU ARE RUNNING FIXTURE RESET AGAINST A NON-DEVELOPER DATABASE, TYPE 'YES' TO PROCEED")
      println("*************************************************************************************")
      breakable {
        while (true) {
          val input = Console.readLine()
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
      /*case Some("update_missing") if geometryFrozen =>
        showFreezeInfo()*/
      case Some("update_road_addresses_geometry") =>
        updateLinearLocationGeometry()
      case Some("import_road_address_change_test_data") =>
        importRoadAddressChangeTestData()
      /*case Some("apply_change_information_to_road_address_links") if geometryFrozen =>
        showFreezeInfo()*/
      case Some("apply_change_information_to_road_address_links") =>
        val numThreads = if (args.length > 1) toIntNumber(args(1)) else numberThreads
        applyChangeInformationToRoadAddressLinks(numThreads)
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

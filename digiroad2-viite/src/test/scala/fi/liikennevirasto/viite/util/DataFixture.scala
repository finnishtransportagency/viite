package fi.liikennevirasto.viite.util

import java.util.Properties

import com.googlecode.flyway.core.Flyway
import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.client.vvh.VVHClient
import fi.liikennevirasto.digiroad2.dao.Queries
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase.ds
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.{MunicipalityCodeImporter, SqlScriptRunner}
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.process._
import fi.liikennevirasto.viite.util.DataImporter.Conversion
import org.joda.time.format.PeriodFormatterBuilder
import org.joda.time.DateTime
import org.scalatra.BadRequest

import scala.collection.parallel.immutable.ParSet
import scala.collection.parallel.ForkJoinTaskSupport
import scala.language.postfixOps

object DataFixture {
  val TestAssetId = 300000
  lazy val properties: Properties = {
    val props = new Properties()
    props.load(getClass.getResourceAsStream("/bonecp.properties"))
    props
  }
  lazy val dr2properties: Properties = {
    val props = new Properties()
    props.load(getClass.getResourceAsStream("/digiroad2.properties"))
    props
  }


  val dataImporter = new DataImporter
  lazy val vvhClient: VVHClient = {
    new VVHClient(dr2properties.getProperty("digiroad2.VVHRestApiEndPoint"))
  }

  val eventBus = new DummyEventBus
  val linkService = new RoadLinkService(vvhClient, eventBus, new DummySerializer)
  val roadAddressDAO = new RoadwayDAO
  val linearLocationDAO = new LinearLocationDAO
  val roadNetworkDAO: RoadNetworkDAO = new RoadNetworkDAO
  val roadAddressService = new RoadAddressService(linkService, roadAddressDAO, linearLocationDAO, roadNetworkDAO, new UnaddressedRoadLinkDAO, new RoadwayAddressMapper(roadAddressDAO, linearLocationDAO), eventBus, properties.getProperty("digiroad2.VVHRoadlink.frozen", "false").toBoolean)

  lazy val continuityChecker = new ContinuityChecker(new RoadLinkService(vvhClient, new DummyEventBus, new DummySerializer))

  private lazy val hms = new PeriodFormatterBuilder() minimumPrintedDigits (2) printZeroAlways() appendHours() appendSeparator (":") appendMinutes() appendSuffix (":") appendSeconds() toFormatter

  private lazy val geometryFrozen: Boolean = dr2properties.getProperty("digiroad2.VVHRoadlink.frozen", "false").toBoolean

  private lazy val numberThreads: Int = 6

  private def toIntNumber(value: Any): Int = {
    try {
      value match {
        case b: Int => b.intValue()
        case _ => value.asInstanceOf[String].toInt
      }
    } catch {
      case e: Exception => numberThreads
    }
  }

  def importRoadAddresses(importTableName: Option[String]): Unit = {
    println(s"\nCommencing road address import from conversion at time: ${DateTime.now()}")
    val vvhClient = new VVHClient(dr2properties.getProperty("digiroad2.VVHRestApiEndPoint"))
    val geometryAdjustedTimeStamp = dr2properties.getProperty("digiroad2.viite.importTimeStamp", "")
    if (geometryAdjustedTimeStamp == "" || geometryAdjustedTimeStamp.toLong == 0L) {
      println(s"****** Missing or bad value for digiroad2.viite.importTimeStamp in properties: '$geometryAdjustedTimeStamp' ******")
    } else {
      println(s"****** Road address geometry timestamp is $geometryAdjustedTimeStamp ******")
      importTableName match {
        case None => // shouldn't get here because args size test
          throw new Exception("****** Import failed! conversiontable name required as second input ******")
        case Some(tableName) =>
          val importOptions = ImportOptions(
            onlyComplementaryLinks = false,
            useFrozenLinkService = geometryFrozen,
            geometryAdjustedTimeStamp.toLong, tableName,
            onlyCurrentRoads = dr2properties.getProperty("digiroad2.importOnlyCurrent", "false").toBoolean)
          dataImporter.importRoadAddressData(Conversion.database(), vvhClient, importOptions)

      }
      println(s"Road address import complete at time: ${DateTime.now()}")
    }

  }

  def updateUnaddressedRoadLink(): Unit = {
    println(s"\nUpdating unaddressed road link table at time: ${DateTime.now()}")
    val vvhClient = new VVHClient(dr2properties.getProperty("digiroad2.VVHRestApiEndPoint"))
    dataImporter.updateUnaddressedRoadLinks(vvhClient)
    println(s"Unaddressed road link update complete at time: ${DateTime.now()}")
    println()
  }

  def updateLinearLocationGeometry(): Unit = {
    println(s"\nUpdating road address table geometries at time: ${DateTime.now()}")
    val vvhClient = new VVHClient(dr2properties.getProperty("digiroad2.VVHRestApiEndPoint"))
    dataImporter.updateLinearLocationGeometry(vvhClient)
    println(s"Road addresses geometry update complete at time: ${DateTime.now()}")
    println()
  }

  //  def findFloatingRoadAddresses(): Unit = {
  //    println(s"\nFinding road addresses that are floating at time: ${DateTime.now()}")
  //    val vvhClient = new VVHClient(dr2properties.getProperty("digiroad2.VVHRestApiEndPoint"))
  //    val username = properties.getProperty("bonecp.username")
  //    val roadLinkService = new RoadLinkService(vvhClient, new DummyEventBus, new DummySerializer)
  //    val roadAddressDAO = new RoadwayDAO
  //    val linearLocationDAO = new LinearLocationDAO
  //    val roadNetworkDAO = new RoadNetworkDAO
  //    val roadAddressService = new RoadAddressService(roadLinkService, roadAddressDAO, linearLocationDAO, roadNetworkDAO, new UnaddressedRoadLinkDAO, new RoadwayAddressMapper(roadAddressDAO, linearLocationDAO), new DummyEventBus)
  //    OracleDatabase.withDynTransaction {
  //      val checker = new RoadNetworkChecker(roadLinkService)
  //      val roads = checker.checkRoadNetwork(username)
  //      println(s"${roads.size} segment(s) found")
  //      roadAddressService.checkRoadAddressFloatingWithoutTX(roads.map(_.id).toSet, float = true)
  //    }
  //    println(s"\nRoad Addresses floating field update complete at time: ${DateTime.now()}")
  //    println()
  //  }

  def checkRoadNetwork(): Unit = {
    println(s"\nstart checking road network at time: ${DateTime.now()}")
    val vvhClient = new VVHClient(dr2properties.getProperty("digiroad2.VVHRestApiEndPoint"))
    val username = properties.getProperty("bonecp.username")
    val roadLinkService = new RoadLinkService(vvhClient, new DummyEventBus, new DummySerializer)
    OracleDatabase.withDynTransaction {
      val checker = new RoadNetworkChecker(roadLinkService)
      checker.checkRoadNetwork(username)
    }
    println(s"\nend checking road network at time: ${DateTime.now()}")
    println()
  }

  private def importComplementaryRoadAddress(): Unit = {
    println(s"\nCommencing complementary road address import at time: ${DateTime.now()}")
    OracleDatabase.withDynTransaction {
      OracleDatabase.setSessionLanguage()
    }
    SqlScriptRunner.runViiteScripts(List(
      "insert_complementary_geometry_data.sql"
    ))
    println(s"complementary road address import completed at time: ${DateTime.now()}")
    println()
  }

  private def importRoadNames(): Unit = {
    SqlScriptRunner.runViiteScripts(List(
      "roadnames.sql"
    ))
  }

  private def importRoadAddressChangeTestData(): Unit = {
    println(s"\nCommencing road address change test data import at time: ${DateTime.now()}")
    OracleDatabase.withDynTransaction {
      OracleDatabase.setSessionLanguage()
    }
    SqlScriptRunner.runViiteScripts(List(
      "insert_road_address_change_test_data.sql"
    ))
    println(s"Road Address Change Test Data import completed at time: ${DateTime.now()}")
    println()
  }

  private def testIntegrationAPIWithAllMunicipalities(): Unit = {
    println(s"\nStarting fetch for integration API for all municipalities")
    val municipalities = OracleDatabase.withDynTransaction {
      Queries.getMunicipalities
    }
    municipalities.foreach(
      municipalityCode =>
        try {
          println(s"\nProcessing municipality $municipalityCode")
          val knownAddressLinksSize = roadAddressService.getAllByMunicipality(municipalityCode).count(ral => ral.roadNumber > 0)
          println(s"\nMunicipality $municipalityCode returned $knownAddressLinksSize links with valid values")
        }
        catch {
          case e: Exception =>
            val message = s"Failed to get road addresses for municipality $municipalityCode"
            println(s"\n" + message + s"\n"+ e.printStackTrace())
        }
    )

  }

  private def applyChangeInformationToRoadAddressLinks(numThreads: Int): Unit = {

    val roadLinkService = new RoadLinkService(vvhClient, new DummyEventBus, new JsonSerializer)
    val linearLocationDAO = new LinearLocationDAO

    println("Clearing cache...")
    roadLinkService.clearCache()
    println("Cache cleaned.")

    val linearLocations =
      OracleDatabase.withDynTransaction {
        linearLocationDAO.fetchCurrentLinearLocations
      }

    println("Total linearLocations " + linearLocations.size)

    //get All municipalities and group them for ely
    OracleDatabase.withDynTransaction {
      MunicipalityDAO.getMunicipalityMapping
    }.groupBy(_._2).foreach {
      case (ely, municipalityEly) =>

        //Get All Municipalities
        val municipalities: ParSet[Long] = municipalityEly.keySet.par
        println("Total municipalities keys for ely " + ely + " -> " + municipalities.size)

        //For each municipality get all VVH Roadlinks
        municipalities.tasksupport = new ForkJoinTaskSupport(new scala.concurrent.forkjoin.ForkJoinPool(numThreads))
        municipalities.foreach { municipality =>
          println("Start processing municipality %d".format(municipality))

        //Obtain all RoadLink by municipality and change info from VVH
        val (roadLinks, changedRoadLinks) = roadLinkService.getRoadLinksAndChangesFromVVH(municipality.toInt, geometryFrozen)
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

  def flyway: Flyway = {
    val flyway = new Flyway()
    flyway.setDataSource(ds)
    flyway.setInitVersion("-1")
    flyway.setInitOnMigrate(true)
    flyway.setLocations("db.migration")
    flyway
  }

  def migrateAll(): Int = {
    flyway.migrate()
  }

  def tearDown() {
    flyway.clean()
  }

  def setUpTest() {
    migrateAll()
    SqlScriptRunner.runScripts(List(
      "insert_users.sql",
      "test_fixture_sequences.sql",
      "insert_road_address_data.sql",
      "insert_floating_road_addresses.sql",
      "insert_overlapping_road_addresses.sql", // Test data for OverLapDataFixture (VIITE-1518)
      "insert_project_link_data.sql",
      "insert_road_names.sql"
    ))
  }

  def importMunicipalityCodes() {
    println("\nCommencing municipality code import at time: ")
    println(DateTime.now())
    new MunicipalityCodeImporter().importMunicipalityCodes()
    println("Municipality code import complete at time: ")
    println(DateTime.now())
    println("\n")
  }

  def main(args: Array[String]): Unit = {
    import scala.util.control.Breaks._
    val username = properties.getProperty("bonecp.username")
    if (!username.startsWith("dr2dev")) {
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

    args.headOption match {
      /*case Some("find_floating_road_addresses") if geometryFrozen =>
        showFreezeInfo()*/
      //      case Some("find_floating_road_addresses") =>
      //        findFloatingRoadAddresses()
      case Some("check_road_network") =>
        checkRoadNetwork()
      case Some("import_road_addresses") =>
        if (args.length > 1)
          importRoadAddresses(Some(args(1)))
        else
          throw new Exception("****** Import failed! conversiontable name required as second input ******")
      case Some("import_complementary_road_address") =>
        importComplementaryRoadAddress()
      /*case Some("update_missing") if geometryFrozen =>
        showFreezeInfo()*/
      case Some("update_missing") =>
        updateUnaddressedRoadLink()
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
        importMunicipalityCodes()
      case Some("test_integration_api_all_municipalities") =>
        testIntegrationAPIWithAllMunicipalities()

      case _ => println("Usage: DataFixture import_road_addresses <conversion table name> | update_missing " +
        "| import_complementary_road_address " +
        "| update_road_addresses_geometry | import_road_address_change_test_data " +
        "| apply_change_information_to_road_address_links | import_road_names | check_road_network")
    }
  }

  case class TimeLine(addressLength: Long, addresses: Seq[RoadAddress])

}

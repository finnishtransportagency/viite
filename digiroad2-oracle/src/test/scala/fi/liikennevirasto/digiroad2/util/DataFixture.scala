package fi.liikennevirasto.digiroad2.util

import java.util.Properties

import com.googlecode.flyway.core.Flyway
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.vvh.VVHClient
import fi.liikennevirasto.digiroad2.dao._
import fi.liikennevirasto.digiroad2.linearasset.{MTKClassWidth, NumericValue, PersistedLinearAsset}
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase._
import fi.liikennevirasto.digiroad2.service.{RoadLinkService}
import fi.liikennevirasto.digiroad2.util.AssetDataImporter.Conversion
import fi.liikennevirasto.digiroad2._
import org.apache.http.impl.client.HttpClientBuilder
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import slick.jdbc.{StaticQuery => Q}


object DataFixture {
  val TestAssetId = 300000
  lazy val properties: Properties = {
    val props = new Properties()
    props.load(getClass.getResourceAsStream("/bonecp.properties"))
    props
  }

  lazy val propertiesDigiroad: Properties = {
    val props = new Properties()
    props.load(getClass.getResourceAsStream("/digiroad2.properties"))
    props
  }

  lazy val dr2properties: Properties = {
    val props = new Properties()
    props.load(getClass.getResourceAsStream("/digiroad2.properties"))
    props
  }

  val dataImporter = new AssetDataImporter
  lazy val vvhClient: VVHClient = {
    new VVHClient(dr2properties.getProperty("digiroad2.VVHRestApiEndPoint"))
  }

  lazy val eventbus: DigiroadEventBus = {
    new DigiroadEventBus
  }

  lazy val geometryTransform: GeometryTransform = {
    new GeometryTransform()
  }
  lazy val roadAddressDao : RoadAddressDAO = {
    new RoadAddressDAO()
  }

  def getProperty(name: String) = {
    val property = propertiesDigiroad.getProperty(name)
    if(property != null)
      property
    else
      throw new RuntimeException(s"cannot find property $name")
  }

  def flyway: Flyway = {
    val flyway = new Flyway()
    flyway.setDataSource(ds)
    flyway.setInitVersion("-1")
    flyway.setInitOnMigrate(true)
    flyway.setLocations("db.migration")
    flyway
  }

  def migrateTo(version: String) = {
    val migrator = flyway
    migrator.setTarget(version.toString)
    migrator.migrate()
  }

  def migrateAll() = {
    flyway.migrate()
  }

  def tearDown() {
    flyway.clean()
  }

  def setUpTest() {
    migrateAll()
    SqlScriptRunner.runScripts(List(
      "insert_test_fixture.sql",
      "insert_users.sql",
      "kauniainen_functional_classes.sql",
      "kauniainen_traffic_directions.sql",
      "kauniainen_link_types.sql",
      "test_fixture_sequences.sql",
      "kauniainen_lrm_positions.sql",
      "insert_road_address_data.sql",
      "insert_floating_road_addresses.sql",
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

  def adjustToNewDigitization(): Unit = {
    println("\nAdjusting side codes and m-values according new digitization directions")
    println(DateTime.now())
    dataImporter.adjustToNewDigitization(dr2properties.getProperty("digiroad2.VVHServiceHost"))
    println("complete at time: ")
    println(DateTime.now())
    println("\n")
  }

  def main(args:Array[String]) : Unit = {

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
      case Some("test") =>
        tearDown()
        setUpTest()
        importMunicipalityCodes()
      case Some("repair") =>
        flyway.repair()
      case Some("adjust_digitization") =>
        adjustToNewDigitization()
      case Some("import_link_ids") =>
        LinkIdImporter.importLinkIdsFromVVH(dr2properties.getProperty("digiroad2.VVHRestApiEndPoint"))
      case _ => println("Usage: DataFixture test | repair | adjust_digitization | import_link_ids"
        )
    }
  }
}

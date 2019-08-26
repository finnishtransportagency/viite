package fi.liikennevirasto.digiroad2.client

import java.util.Properties

import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset.BoundingRectangle
import fi.liikennevirasto.digiroad2.client.vvh.VVHClient
import org.scalatest.{FunSuite, Matchers}

class VVHClientSpec extends FunSuite with Matchers {
  lazy val properties: Properties = {
    val props = new Properties()
    props.load(getClass.getResourceAsStream("/digiroad2.properties"))
    props
  }

  test("Test fetchByMunicipalitiesAndBounds When connecting to VVH complementary and retrieve result Then it should not throw error") {
    val vvhClient = new VVHClient(properties.getProperty("digiroad2.VVHRestApiEndPoint"))
    vvhClient.complementaryData.fetchByMunicipalitiesAndBounds(BoundingRectangle(Point(564000, 6930000), Point(566000, 6931000)), Set(420))
  }

}


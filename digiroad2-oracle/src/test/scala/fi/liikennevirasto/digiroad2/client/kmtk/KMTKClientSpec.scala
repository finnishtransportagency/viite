package fi.liikennevirasto.digiroad2.client.kmtk

import java.util.Properties

import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset.BoundingRectangle
import org.scalatest.{FunSuite, Matchers}

class KMTKClientSpec extends FunSuite with Matchers {

  lazy val properties: Properties = {
    val props = new Properties()
    props.load(getClass.getResourceAsStream("/digiroad2.properties"))
    props
  }

  test("Test fetchByBounds When giving some bounding box Then should return some data") {
    val kmtkClient = new KMTKClient(properties.getProperty("digiroad2.KMTKRestApiEndPoint"))
    val result = kmtkClient.roadLinkData.fetchByBounds(BoundingRectangle(Point(445000, 7000000), Point(446000, 7005244)))
    result.size should be > 1
  }

}


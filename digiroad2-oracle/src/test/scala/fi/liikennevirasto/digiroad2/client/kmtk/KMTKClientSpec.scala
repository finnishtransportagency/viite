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

  val restEndPoint = properties.getProperty("digiroad2.KMTKRestApiEndPoint")

  val kmtkClient = new KMTKClient(restEndPoint)

  test("Test roadLinkData.serviceUrl When no required parameters given Then should throw KMTKClientException") {
    val thrown = intercept[KMTKClientException] {
      kmtkClient.roadLinkData.serviceUrl(None, None, None, "", "")
    }
    thrown should not be null
  }

  test("Test inputStreamToFeatureCollection When got valid json response Then should return FeatureCollection") {
    val featureCollection = kmtkClient.roadLinkData.inputStreamToFeatureCollection(getClass.getResourceAsStream("/kmtk-roadlink-bbox.json"))
    featureCollection.isDefined should be(true)
  }

  // TODO Use mock response data instead of calling real KMTK interface
  test("Test fetchByBounds When giving some bounding box Then should return some data") {
    val result = kmtkClient.roadLinkData.fetchByBounds(BoundingRectangle(Point(445000, 7000000), Point(446000, 7005244)))
    result.size should be > 1
  }

}


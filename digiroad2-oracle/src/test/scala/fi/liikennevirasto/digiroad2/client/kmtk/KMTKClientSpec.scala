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

  val epsilon = 0.00000001

  private val restEndPoint: String = properties.getProperty("digiroad2.KMTKRestApiEndPoint")

  val kmtkClient = new KMTKClient(restEndPoint)

  private def mockFeatureCollection = {
    kmtkClient.roadLinkData.inputStreamToFeatureCollection(getClass.getResourceAsStream("/kmtk-roadlink-bbox.json"))
  }

  test("Test inputStreamToFeatureCollection When got valid json response Then should return FeatureCollection") {
    val featureCollection = mockFeatureCollection
    featureCollection.isDefined should be(true)
  }

  test("Test inputStreamToFeatureCollection When got valid json response Then should include points with correct m-values") {
    val featureCollection = mockFeatureCollection
    val horizontalLength = featureCollection.get.features.head.properties.horizontalLength
    val geometryWithM = featureCollection.get.features.head.geometry.toPointsWithM
    val p1 = geometryWithM.head
    p1.x should be > epsilon
    p1.y should be > epsilon
    p1.z should be(0.0 +- epsilon)
    p1.m should be(0.0 +- epsilon)
    val p1b = geometryWithM.last
    p1b.x should be > epsilon
    p1b.y should be > epsilon
    p1b.z should be(0.0 +- epsilon)
    p1b.m should be(horizontalLength +- epsilon)
/*    val p2 = featureCollection.get.features.head.properties.startNode.toPoint.get
    p2.x should be > epsilon
    p2.y should be > epsilon
    p2.z should not be (0.0 +- epsilon)
    val p3 = featureCollection.get.features.head.properties.endNode.toPoint.get
    p3.x should be > epsilon
    p3.y should be > epsilon
    p3.z should not be (0.0 +- epsilon)*/
  }

  test("Test inputStreamToFeatureCollection When got valid json response Then should include dates") {
    val featureCollection = mockFeatureCollection
    val properties = featureCollection.get.features.head.properties
    properties.createdAtAsDateTime.isDefined should be(true)
    properties.sourceStartDateAsDateTime.isDefined should be(true)
  }

  test("Test KMTKProperties.*AsDateTime When valid date string Then should parse DateTime") {
    val dateString = "2019-06-26T12:12:47.354+03:00"
    val p = KMTKProperties("", "", 0, dateString, Some(dateString), dateString, Some(dateString),
      None, None, 0, 0, None, None, None, 0, None, None, None, None, None, 0, 0, 0, 0, "", None, None, None, None, "",
      geometryFlip = false, 0.0, None, 0, 0)
    p.createdAtAsDateTime.isDefined should be(true)
    val expectedDate = "2019-06-26 12:12:47"
    p.createdAtAsDateTime.get.toString("yyyy-MM-dd HH:mm:ss") should be(expectedDate)
    p.modifiedAtAsDateTime.isDefined should be(true)
    p.modifiedAtAsDateTime.get.toString("yyyy-MM-dd HH:mm:ss") should be(expectedDate)
    p.sourceStartDateAsDateTime.isDefined should be(true)
    p.sourceStartDateAsDateTime.get.toString("yyyy-MM-dd HH:mm:ss") should be(expectedDate)
    p.endedAtAsDateTime.isDefined should be(true)
    p.endedAtAsDateTime.get.toString("yyyy-MM-dd HH:mm:ss") should be(expectedDate)
  }

  // TODO Use mock response data instead of calling the real KMTK interface (We cannot put the credentials in the version control)
  test("Test fetchByBounds When giving some bounding box Then should return some data") {
    val result = kmtkClient.roadLinkData.fetchByBounds(BoundingRectangle(Point(445000, 7000000), Point(446000, 7005244)))
    result.size should be > 1
  }

}

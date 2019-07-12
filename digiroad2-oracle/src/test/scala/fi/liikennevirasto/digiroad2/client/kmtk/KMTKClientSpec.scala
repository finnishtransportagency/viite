package fi.liikennevirasto.digiroad2.client.kmtk

import java.net.URLEncoder
import java.util.Properties

import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset.BoundingRectangle
import org.joda.time.DateTime
import org.scalatest.{FunSuite, Matchers}

class KMTKClientSpec extends FunSuite with Matchers {

  lazy val properties: Properties = {
    val props = new Properties()
    props.load(getClass.getResourceAsStream("/digiroad2.properties"))
    props
  }

  private val restEndPoint: String = properties.getProperty("digiroad2.KMTKRestApiEndPoint")

  val kmtkClient = new KMTKClient(restEndPoint)

  private def mockFeatureCollection = {
    kmtkClient.roadLinkData.inputStreamToFeatureCollection(getClass.getResourceAsStream("/kmtk-roadlink-bbox.json"))
  }

  test("Test inputStreamToFeatureCollection When got valid json response Then should return FeatureCollection") {
    val featureCollection = mockFeatureCollection
    featureCollection.isDefined should be(true)
  }

  test("Test inputStreamToFeatureCollection When got valid json response Then should include points") {
    val featureCollection = mockFeatureCollection
    val p1 = featureCollection.get.features.head.geometry.toPoints.head
    p1.x should be > 0.0
    p1.y should be > 0.0
    p1.z should not be 0.0
    val p1b = featureCollection.get.features.head.geometry.toPoints.last
    p1b.x should be > 0.0
    p1b.y should be > 0.0
    p1b.z should not be 0.0
    val p2 = featureCollection.get.features.head.properties.startNode.toPoint.get
    p2.x should be > 0.0
    p2.y should be > 0.0
    p2.z should not be 0.0
    val p3 = featureCollection.get.features.head.properties.endNode.toPoint.get
    p3.x should be > 0.0
    p3.y should be > 0.0
    p3.z should not be 0.0
  }

  test("Test roadLinkData.timespanParam When valid given dates Then should return correctly formatted timespan parameter string") {
    val from = "2019-07-01T01:02:03.123+03:00"
    val to = "2019-12-31T04:05:06.456+02:00"
    val fromDateTime = DateTime.parse(from)
    val toDateTime = DateTime.parse(to)
    val timespan = kmtkClient.roadLinkData.timespanParam(fromDateTime, toDateTime)
    timespan should be("timespan=" + URLEncoder.encode(s"""{"from":"$from","to":"$to"}""", "UTF-8"))
  }

  test("Test inputStreamToFeatureCollection When got valid json response Then should include dates") {
    val featureCollection = mockFeatureCollection
    val properties = featureCollection.get.features.head.properties
    properties.createdAt.length should be > 0
    properties.sourceStartDate.length should be > 0
  }

  test("Test KMTKProperties.*AsDateTime When valid date string Then should parse DateTime") {
    val dateString = "2019-06-26T12:12:47.354+03:00"
    val p = KMTKProperties(null, None, 0, 0, null, None, None, 0, 0, 0, 0.0, 0, dateString, dateString, Some(dateString),
      Some(dateString), null, 0, 0, "", 0, geoMetryFlip = false, null, null, 0, 0)
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

  // TODO Use mock response data instead of calling real KMTK interface
  test("Test fetchByBounds When giving some bounding box Then should return some data") {
    val result = kmtkClient.roadLinkData.fetchByBounds(BoundingRectangle(Point(445000, 7000000), Point(446000, 7005244)))
    result.size should be > 1
  }

}


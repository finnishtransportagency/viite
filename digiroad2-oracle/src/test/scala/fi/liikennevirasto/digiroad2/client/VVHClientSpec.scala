package fi.liikennevirasto.digiroad2.client

import java.util.Properties

import com.vividsolutions.jts.geom.GeometryFactory
import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset.BoundingRectangle
import fi.liikennevirasto.digiroad2.client.vvh.VVHClient
import org.geotools.geometry.jts.GeometryBuilder
import org.scalatest.{FunSuite, Matchers}

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class VVHClientSpec extends FunSuite with Matchers{
  lazy val properties: Properties = {
    val props = new Properties()
    props.load(getClass.getResourceAsStream("/digiroad2.properties"))
    props
  }

  val geomFact= new GeometryFactory()
  val geomBuilder = new GeometryBuilder(geomFact)

  /**
    * Checks that VVH history bounding box search works uses API example bounding box so it should receive results
    */
  test("Test fetchByMunicipalitiesAndBounds When connecting to VVH roadLink history and retrieve result Then it should indeed result some") {
    val vvhClient= new VVHClient(properties.getProperty("digiroad2.VVHRestApiEndPoint"))
    val result= vvhClient.historyData.fetchByMunicipalitiesAndBounds(BoundingRectangle(Point(564000, 6930000),Point(566000, 6931000)), Set(420))
    result.size should be >1
  }

  /**
    * Checks that VVH history link id search works and returns something
    */
  test("Test fetchVVHRoadLinkByLinkIds When connecting to VVH roadLink history and giving some linkIds Then should be returned some data") {
    val vvhClient= new VVHClient(properties.getProperty("digiroad2.VVHRestApiEndPoint"))
    val result = vvhClient.historyData.fetchVVHRoadLinkByLinkIds(Set(440484,440606,440405,440489))
    result.nonEmpty should be (true)
  }

  test("Test fetchByBoundsAndMunicipalities When connecting to VVH ChangeInfo and giving some bounding box Then should return some data") {
    val vvhClient= new VVHClient(properties.getProperty("digiroad2.VVHRestApiEndPoint"))
    val result= vvhClient.roadLinkChangeInfo.fetchByBoundsAndMunicipalities(BoundingRectangle(Point(532578.3338013917,6993401.605560873,0.0),Point(532978.3338013917,6994261.605560873,0.0)), Set.empty[Int])
    result.size should be >1
  }

  /**
    * Test for frozen december 15.12.2016 VVH API: No test cases writen to documentation so test might fail for not having any links
  */

  test("Test fetchByBounds When giving some bounding box Then should return some data") {
    val frozenApiEnabled = properties.getProperty("digiroad2.VVHRoadlink.frozen")
    if (frozenApiEnabled=="true") { //Api only exists in QA and Production
      val vvhClient= new VVHClient(properties.getProperty("digiroad2.VVHRestApiEndPoint"))
      val result= vvhClient.frozenTimeRoadLinkData.fetchByBounds(BoundingRectangle(Point(445000, 7000000),Point(446000, 7005244)))
      result.size should be >1
    }
  }

  test("Test fetchByLinkIds When connecting to VVH ChangeInfo and giving one linkId Then should return some data") {
    val vvhClient= new VVHClient(properties.getProperty("digiroad2.VVHRestApiEndPoint"))
    val result = vvhClient.roadLinkChangeInfo.fetchByLinkIds(Set(5176799))
    result.nonEmpty should be (true)
  }
}


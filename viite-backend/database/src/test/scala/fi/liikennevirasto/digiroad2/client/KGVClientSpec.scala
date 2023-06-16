package fi.liikennevirasto.digiroad2.client

import org.locationtech.jts.geom.GeometryFactory
import fi.liikennevirasto.digiroad2.client.kgv.KgvRoadLink
import fi.liikennevirasto.digiroad2.util.ViiteProperties
import fi.vaylavirasto.viite.geometry.{BoundingRectangle, Point}
import fi.vaylavirasto.viite.model.RoadLink
import org.geotools.geometry.jts.GeometryBuilder
import org.scalatest.{FunSuite, Matchers}

class KGVClientSpec extends FunSuite with Matchers {

  val geomFact = new GeometryFactory()
  val geomBuilder = new GeometryBuilder(geomFact)

  /**
    * Checks that VVH history bounding box search works uses API example bounding box so it should receive results
    */
//  test("Test fetchByMunicipalitiesAndBounds When connecting to VVH roadLink history and retrieve result Then it should indeed result some") {
//    val vvhClient = new KgvRoadLink
//    val result = vvhClient.historyData.fetchByMunicipalitiesAndBounds(BoundingRectangle(Point(564000, 6930000), Point(566000, 6931000)), Set(420))
//    result.size should be > 1
//  }

  /**
    * Checks that VVH history link id search works and returns something
    */
//  test("Test fetchVVHRoadLinkByLinkIds When connecting to VVH roadLink history and giving some linkIds Then should be returned some data") {
//    val vvhClient               = new KgvRoadLink
//    val result: HistoryRoadLink = vvhClient.historyData.fetchVVHRoadLinkByLinkIds(Set(440484, 440606, 440405, 440489))
//    result.nonEmpty should be(true)
//  }
//
//  test("Test fetchByBoundsAndMunicipalities When connecting to VVH ChangeInfo and giving some bounding box Then should return some data") {
//    val vvhClient = new KgvRoadLink
//    val result = vvhClient.roadLinkChangeInfo.fetchByBoundsAndMunicipalities(BoundingRectangle(Point(532578.3338013917, 6993401.605560873, 0.0), Point(532978.3338013917, 6994261.605560873, 0.0)), Set.empty[Int])
//    result.size should be > 1
//  }

  /**
    * Test for frozen december 15.12.2016 VVH API: No test cases written to documentation so test might fail for not having any links
    */

  test("Test fetchByBounds When giving some bounding box Then should return some data") {
    val frozenApiEnabled = ViiteProperties.kgvRoadlinkFrozen
    if (frozenApiEnabled) {
      val KGVClient = new KgvRoadLink
      val result = KGVClient.frozenTimeRoadLinkData.queryByMunicipalitiesAndBounds(BoundingRectangle(Point(445000, 7000000), Point(446000, 7005244)), Set[Int]())
      result.size should be > 1
      result.head.getClass.getTypeName should be (classOf[RoadLink].getCanonicalName)
    }
  }

//  test("Test fetchByLinkIds When connecting to VVH ChangeInfo and giving one linkId Then should return some data") {
//    val vvhClient = new KgvRoadLink
//    val result = vvhClient.roadLinkChangeInfo.fetchByLinkIds(Set(5176799.toString))
//    result.nonEmpty should be(true)
//  }
}


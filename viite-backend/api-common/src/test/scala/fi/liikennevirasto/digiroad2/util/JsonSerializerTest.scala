package fi.liikennevirasto.digiroad2.util

import fi.liikennevirasto.digiroad2.client.kgv.{ChangeInfo, ChangeType}
import fi.vaylavirasto.viite.geometry.Point
import fi.vaylavirasto.viite.model.{AdministrativeClass, RoadLink, TrafficDirection}
import java.io.File
import org.scalatest.{FunSuite, Matchers}

class JsonSerializerTest extends FunSuite with Matchers {

  val serializer = new fi.liikennevirasto.viite.util.JsonSerializer
  test("testWriteReadCachedGeometry") {
    val f = File.createTempFile("test", ".cache")
    val roadLinks = Seq(RoadLink(1L.toString, Seq(Point(0.0, 1.0),Point(0.1, 2.0)), 1.1, AdministrativeClass.State, TrafficDirection.BothDirections, Option("yesterday"), modifiedBy = Option("someone"), municipalityCode = 257, sourceId = ""),
      RoadLink(2L.toString, Seq(Point(2.0, 1.0),Point(0.1, 2.0)), 1.1, AdministrativeClass.State, TrafficDirection.BothDirections, Option("yesterday"), modifiedBy = Option("someone"), municipalityCode = 257, sourceId = ""))
    serializer.writeCache(f, roadLinks) should be (true)
    val result = serializer.readCachedGeometry(f)
    result.last should be (roadLinks.last)
  }

  // Takes some time to run, run manually if needed.
  ignore("testWriteHugeCachedGeometry") {
    val f = File.createTempFile("test", ".cache")
    val roadLink = RoadLink(1.toString, Seq(Point(0.0, 1.0),Point(0.1, 2.0)), 1.1, AdministrativeClass.State, TrafficDirection.BothDirections, None, modifiedBy = Option("someone"), municipalityCode = 257, sourceId = "")
    val hugeList = List.range(1, 500000).map(i => roadLink.copy(linkId = i.toString, municipalityCode = 257, sourceId = ""))
    serializer.writeCache(f, hugeList) should be (true)
    f.length() > 1048576 should be (true)
  }

  test("testWriteReadCachedChanges") {
    val f = File.createTempFile("test", ".cache")
    val changes = Seq(ChangeInfo(Option(1L.toString), Option(2L.toString), 3L, ChangeType.LengthenedNewPart, Option(0.0), Option(1.0), Option(1.5), Option(2.5), 10L))
    serializer.writeCache(f, changes) should be (true)
    val result = serializer.readCachedChanges(f)
    result should be (changes)
  }

}

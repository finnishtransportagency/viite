package fi.liikennevirasto.digiroad2.util

import com.github.tototoshi.slick.MySQLJodaSupport._
import fi.liikennevirasto.digiroad2.linearasset._
import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.asset.LinkGeomSource.NormalLinkInterface
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.vvh._
import fi.liikennevirasto.digiroad2.dao.{Queries, Sequences}
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.{RoadLinkOTHService, RoadLinkService}
import org.joda.time.DateTime
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery
import slick.jdbc.StaticQuery.interpolation
import org.mockito.Matchers._
import org.mockito.Mockito._

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class AssetDataImporterSpec extends FunSuite with Matchers {
  private val assetDataImporter = new AssetDataImporter {
    override def withDynTransaction(f: => Unit): Unit = f
    override def withDynSession[T](f: => T): T = f
  }

  private val CommonAttributes = Seq("MUNICIPALITYCODE" -> BigInt(853), "VERTICALLEVEL" -> 0.0).toMap;

  test("Batch drivers chunck size") {
    assetDataImporter.getBatchDrivers(1, 10000, 1000)
      .map( chunk => (chunk._2 - chunk._1) + 1)
      .foreach { chunkSize => chunkSize shouldBe 1000 }
  }

  private def prohibitionSegment(id: Long = 1l,
                                 linkId: Long = 1l,
                                 startMeasure: Double = 0.0,
                                 endMeasure: Double = 1.0,
                                 municipality: Int = 235,
                                 value: Int = 2,
                                 sideCode: Int = 1,
                                 validityPeriod: Option[String] = None):
  (Long, Long, Double, Double, Int, Int, Int, Option[String]) = {
    (id, linkId, startMeasure, endMeasure, municipality, value, sideCode, validityPeriod)
  }

  test("Two prohibition segments on the same link produces one asset with two prohibition values") {
    val segment1 = prohibitionSegment()
    val segment2 = prohibitionSegment(id = 2l, value = 4)
    val prohibitionSegments = Seq(segment1, segment2)
    val roadLink = VVHRoadlink(1l, 235, Seq(Point(0.0, 0.0), Point(1.0, 0.0)), Municipality, TrafficDirection.BothDirections, FeatureClass.AllOthers, attributes = CommonAttributes)
    val roadLinks: Seq[VVHRoadlink] = Seq(roadLink)

    val result: Seq[Either[String, PersistedLinearAsset]] = assetDataImporter.convertToProhibitions(prohibitionSegments, roadLinks, Nil)

    val expectedValue = Some(Prohibitions(Seq(ProhibitionValue(2, Set.empty, Set.empty), ProhibitionValue(4, Set.empty, Set.empty))))
    result should be(Seq(Right(PersistedLinearAsset(0l, 1l, 1, expectedValue, 0.0, 1.0, None, None, None, None, false, 190, 0, None, LinkGeomSource.NormalLinkInterface, None, None))))
  }

  test("Two prohibition segments on the same link with different side codes produces two assets with one prohibition value") {
    val segment1 = prohibitionSegment(sideCode = 2)
    val segment2 = prohibitionSegment(id = 2l, value = 4, sideCode = 3)
    val prohibitionSegments = Seq(segment1, segment2)
    val roadLink = VVHRoadlink(1l, 235, Seq(Point(0.0, 0.0), Point(1.0, 0.0)), Municipality, TrafficDirection.BothDirections, FeatureClass.AllOthers, attributes = CommonAttributes)
    val roadLinks: Seq[VVHRoadlink] = Seq(roadLink)

    val result: Set[Either[String, PersistedLinearAsset]] = assetDataImporter.convertToProhibitions(prohibitionSegments, roadLinks, Nil).toSet

    val conversionResult1 = Right(PersistedLinearAsset(0l, 1l, 2, Some(Prohibitions(Seq(ProhibitionValue(2, Set.empty, Set.empty)))), 0.0, 1.0, None, None, None, None, false, 190, 0, None, LinkGeomSource.NormalLinkInterface, None, None))
    val conversionResult2 = Right(PersistedLinearAsset(0l, 1l, 3, Some(Prohibitions(Seq(ProhibitionValue(4, Set.empty, Set.empty)))), 0.0, 1.0, None, None, None, None, false, 190, 0, None, LinkGeomSource.NormalLinkInterface, None, None))
    result should be(Set(conversionResult1, conversionResult2))
  }

  test("Two-sided prohibition segment and one-sided prohibition segment produces two assets with combined prohibitions on one side") {
    val segment1 = prohibitionSegment()
    val segment2 = prohibitionSegment(id = 2l, value = 4, sideCode = 3)
    val prohibitionSegments = Seq(segment1, segment2)
    val roadLink = VVHRoadlink(1l, 235, Seq(Point(0.0, 0.0), Point(1.0, 0.0)), Municipality, TrafficDirection.BothDirections, FeatureClass.AllOthers, attributes = CommonAttributes)
    val roadLinks: Seq[VVHRoadlink] = Seq(roadLink)

    val result: Set[Either[String, PersistedLinearAsset]] = assetDataImporter.convertToProhibitions(prohibitionSegments, roadLinks, Nil).toSet

    val conversionResult1 = Right(PersistedLinearAsset(0l, 1l, 2, Some(Prohibitions(Seq(ProhibitionValue(2, Set.empty, Set.empty)))), 0.0, 1.0, None, None, None, None, false, 190, 0, None, LinkGeomSource.NormalLinkInterface, None, None))
    val conversionResult2 = Right(PersistedLinearAsset(0l, 1l, 3, Some(Prohibitions(Seq(ProhibitionValue(2, Set.empty, Set.empty), ProhibitionValue(4, Set.empty, Set.empty)))), 0.0, 1.0, None, None, None, None, false, 190, 0, None, LinkGeomSource.NormalLinkInterface, None, None))
    result should be(Set(conversionResult1, conversionResult2))
  }

  test("Segment without associated road link from VVH is dropped") {
    val segment1 = prohibitionSegment()
    val prohibitionSegments = Seq(segment1)
    val roadLinks: Seq[VVHRoadlink] = Nil

    val result: Set[Either[String, PersistedLinearAsset]] = assetDataImporter.convertToProhibitions(prohibitionSegments, roadLinks, Nil).toSet

    result should be(Set(Left("No VVH road link found for mml id 1. 1 dropped.")))
  }

  test("Drop prohibition segments of type maintenance drive and drive to plot") {
    val segment1 = prohibitionSegment(value = 21)
    val segment2 = prohibitionSegment(id = 2l, value = 22)
    val prohibitionSegments = Seq(segment1, segment2)
    val roadLink = VVHRoadlink(1l, 235, Seq(Point(0.0, 0.0), Point(1.0, 0.0)), Municipality, TrafficDirection.BothDirections, FeatureClass.AllOthers, attributes = CommonAttributes)
    val roadLinks: Seq[VVHRoadlink] = Seq(roadLink)

    val result: Set[Either[String, PersistedLinearAsset]] = assetDataImporter.convertToProhibitions(prohibitionSegments, roadLinks, Nil).toSet

    result should be(Set(Left("Invalid type for prohibition. 1 dropped."), Left("Invalid type for prohibition. 2 dropped.")))
  }

  test("Adjust segment measurements to road link") {
    val segment1 = prohibitionSegment(endMeasure = 0.5)
    val prohibitionSegments = Seq(segment1)
    val roadLink = VVHRoadlink(1l, 235, Seq(Point(0.0, 0.0), Point(1.0, 0.0)), Municipality, TrafficDirection.BothDirections, FeatureClass.AllOthers, attributes = CommonAttributes)
    val roadLinks: Seq[VVHRoadlink] = Seq(roadLink)

    val result: Set[Either[String, PersistedLinearAsset]] = assetDataImporter.convertToProhibitions(prohibitionSegments, roadLinks, Nil).toSet

    val conversionResult1 = Right(PersistedLinearAsset(0l, 1l, 1, Some(Prohibitions(Seq(ProhibitionValue(2, Set.empty, Set.empty)))), 0.0, 1.0, None, None, None, None, false, 190, 0, None, LinkGeomSource.NormalLinkInterface, None, None))
    result should be(Set(conversionResult1))
  }

  test("Include exception in prohibition value") {
    val segment1 = prohibitionSegment()
    val prohibitionSegments = Seq(segment1)
    val roadLink = VVHRoadlink(1l, 235, Seq(Point(0.0, 0.0), Point(1.0, 0.0)), Municipality, TrafficDirection.BothDirections, FeatureClass.AllOthers, attributes = CommonAttributes)
    val roadLinks: Seq[VVHRoadlink] = Seq(roadLink)
    val exceptions = Seq((1l, 1l, 8, 1))

    val result: Set[Either[String, PersistedLinearAsset]] = assetDataImporter.convertToProhibitions(prohibitionSegments, roadLinks, exceptions).toSet

    val conversionResult1 = Right(PersistedLinearAsset(0l, 1l, 1, Some(Prohibitions(Seq(ProhibitionValue(2, Set.empty, Set(8))))), 0.0, 1.0, None, None, None, None, false, 190, 0, None, LinkGeomSource.NormalLinkInterface, None, None))
    result should be(Set(conversionResult1))
  }

  test("Exceptions that do not relate to prohibition are not included") {
    val segment1 = prohibitionSegment()
    val prohibitionSegments = Seq(segment1)
    val roadLink = VVHRoadlink(1l, 235, Seq(Point(0.0, 0.0), Point(1.0, 0.0)), Municipality, TrafficDirection.BothDirections, FeatureClass.AllOthers, attributes = CommonAttributes)
    val roadLinks: Seq[VVHRoadlink] = Seq(roadLink)
    val exceptions = Seq((1l, 2l, 8, 1))

    val result: Set[Either[String, PersistedLinearAsset]] = assetDataImporter.convertToProhibitions(prohibitionSegments, roadLinks, exceptions).toSet

    val conversionResult1 = Right(PersistedLinearAsset(0l, 1l, 1, Some(Prohibitions(Seq(ProhibitionValue(2, Set.empty, Set.empty)))), 0.0, 1.0, None, None, None, None, false, 190, 0, None, LinkGeomSource.NormalLinkInterface, None, None))
    val conversionResult2 = Left("No prohibition found on mml id 2. Dropped exception 1.")
    result should be(Set(conversionResult1, conversionResult2))
  }

  test("Filter out exceptions that allow all traffic") {
    val segment1 = prohibitionSegment()
    val prohibitionSegments = Seq(segment1)
    val roadLink = VVHRoadlink(1l, 235, Seq(Point(0.0, 0.0), Point(1.0, 0.0)), Municipality, TrafficDirection.BothDirections, FeatureClass.AllOthers, attributes = CommonAttributes)
    val roadLinks: Seq[VVHRoadlink] = Seq(roadLink)
    val exceptions = Seq((1l, 1l, 1, 1))

    val result: Set[Either[String, PersistedLinearAsset]] = assetDataImporter.convertToProhibitions(prohibitionSegments, roadLinks, exceptions).toSet

    val conversionResult1 = Right(PersistedLinearAsset(0l, 1l, 1, Some(Prohibitions(Seq(ProhibitionValue(2, Set.empty, Set.empty)))), 0.0, 1.0, None, None, None, None, false, 190, 0, None, LinkGeomSource.NormalLinkInterface, None, None))
    val conversionResult2 = Left("Invalid exception. Dropped exception 1.")
    result should be(Set(conversionResult1, conversionResult2))
  }

  test("Filter out exceptions with exception codes not supported") {
    val segment1 = prohibitionSegment()
    val prohibitionSegments = Seq(segment1)
    val roadLink = VVHRoadlink(1l, 235, Seq(Point(0.0, 0.0), Point(1.0, 0.0)), Municipality, TrafficDirection.BothDirections, FeatureClass.AllOthers, attributes = CommonAttributes)
    val roadLinks: Seq[VVHRoadlink] = Seq(roadLink)
    val exceptions = Seq((1l, 1l, 20, 1))

    val result: Set[Either[String, PersistedLinearAsset]] = assetDataImporter.convertToProhibitions(prohibitionSegments, roadLinks, exceptions).toSet

    val conversionResult1 = Right(PersistedLinearAsset(0l, 1l, 1, Some(Prohibitions(Seq(ProhibitionValue(2, Set.empty, Set.empty)))), 0.0, 1.0, None, None, None, None, false, 190, 0, None, LinkGeomSource.NormalLinkInterface, None, None))
    val conversionResult2 = Left("Invalid exception. Dropped exception 1.")
    result should be(Set(conversionResult1, conversionResult2))
  }

  test("Exception affects prohibition with same side code") {
    val segment1 = prohibitionSegment(sideCode = 2)
    val segment2 = prohibitionSegment(id = 2l, value = 4, sideCode = 3)
    val prohibitionSegments = Seq(segment1, segment2)
    val roadLink = VVHRoadlink(1l, 235, Seq(Point(0.0, 0.0), Point(1.0, 0.0)), Municipality, TrafficDirection.BothDirections, FeatureClass.AllOthers, attributes = CommonAttributes)
    val roadLinks: Seq[VVHRoadlink] = Seq(roadLink)
    val exceptions = Seq((1l, 1l, 8, 2))

    val result: Set[Either[String, PersistedLinearAsset]] = assetDataImporter.convertToProhibitions(prohibitionSegments, roadLinks, exceptions).toSet

    val conversionResult1 = Right(PersistedLinearAsset(0l, 1l, 2, Some(Prohibitions(Seq(ProhibitionValue(2, Set.empty, Set(8))))), 0.0, 1.0, None, None, None, None, false, 190, 0, None, LinkGeomSource.NormalLinkInterface, None, None))
    val conversionResult2 = Right(PersistedLinearAsset(0l, 1l, 3, Some(Prohibitions(Seq(ProhibitionValue(4, Set.empty, Set.empty)))), 0.0, 1.0, None, None, None, None, false, 190, 0, None, LinkGeomSource.NormalLinkInterface, None, None))
    result should be(Set(conversionResult1, conversionResult2))
  }

  test("One sided exception splits two sided prohibition") {
    val segment1 = prohibitionSegment()
    val prohibitionSegments = Seq(segment1)
    val roadLink = VVHRoadlink(1l, 235, Seq(Point(0.0, 0.0), Point(1.0, 0.0)), Municipality, TrafficDirection.BothDirections, FeatureClass.AllOthers, attributes = CommonAttributes)
    val roadLinks: Seq[VVHRoadlink] = Seq(roadLink)
    val exceptions = Seq((1l, 1l, 8, 2), (1l, 1l, 9, 3))

    val result: Set[Either[String, PersistedLinearAsset]] = assetDataImporter.convertToProhibitions(prohibitionSegments, roadLinks, exceptions).toSet

    val conversionResult1 = Right(PersistedLinearAsset(0l, 1l, 2, Some(Prohibitions(Seq(ProhibitionValue(2, Set.empty, Set(8))))), 0.0, 1.0, None, None, None, None, false, 190, 0, None, LinkGeomSource.NormalLinkInterface, None, None))
    val conversionResult2 = Right(PersistedLinearAsset(0l, 1l, 3, Some(Prohibitions(Seq(ProhibitionValue(2, Set.empty, Set(9))))), 0.0, 1.0, None, None, None, None, false, 190, 0, None, LinkGeomSource.NormalLinkInterface, None, None))
    result should be(Set(conversionResult1, conversionResult2))
  }

  test("Two sided exceptions affect one sided prohibitions") {
    val segment1 = prohibitionSegment(sideCode = 2)
    val segment2 = prohibitionSegment(id = 2l, value = 4, sideCode = 3)
    val prohibitionSegments = Seq(segment1, segment2)
    val roadLink = VVHRoadlink(1l, 235, Seq(Point(0.0, 0.0), Point(1.0, 0.0)), Municipality, TrafficDirection.BothDirections, FeatureClass.AllOthers, attributes = CommonAttributes)
    val roadLinks: Seq[VVHRoadlink] = Seq(roadLink)
    val exceptions = Seq((1l, 1l, 8, 1))

    val result: Set[Either[String, PersistedLinearAsset]] = assetDataImporter.convertToProhibitions(prohibitionSegments, roadLinks, exceptions).toSet

    val conversionResult1 = Right(PersistedLinearAsset(0l, 1l, 2, Some(Prohibitions(Seq(ProhibitionValue(2, Set.empty, Set(8))))), 0.0, 1.0, None, None, None, None, false, 190, 0, None, LinkGeomSource.NormalLinkInterface, None, None))
    val conversionResult2 = Right(PersistedLinearAsset(0l, 1l, 3, Some(Prohibitions(Seq(ProhibitionValue(4, Set.empty, Set(8))))), 0.0, 1.0, None, None, None, None, false, 190, 0, None, LinkGeomSource.NormalLinkInterface, None, None))
    result should be(Set(conversionResult1, conversionResult2))
  }

  test("Parse validity period into prohibition") {
    val segment = prohibitionSegment(validityPeriod = Some("[[(h8){h7}]*[(t2){d5}]]"))
    val prohibitionSegments = Seq(segment)
    val roadLink = VVHRoadlink(1l, 235, Seq(Point(0.0, 0.0), Point(1.0, 0.0)), Municipality, TrafficDirection.BothDirections, FeatureClass.AllOthers, attributes = CommonAttributes)
    val roadLinks: Seq[VVHRoadlink] = Seq(roadLink)

    val result: Set[Either[String, PersistedLinearAsset]] = assetDataImporter.convertToProhibitions(prohibitionSegments, roadLinks, Nil).toSet

    val expectedValidityPeriods = Set(ValidityPeriod(8, 15, ValidityPeriodDayOfWeek.Weekday))
    val expectedConversionResult = Right(PersistedLinearAsset(0l, 1l, 1, Some(Prohibitions(Seq(ProhibitionValue(2, expectedValidityPeriods, Set.empty)))), 0.0, 1.0, None, None, None, None, false, 190, 0, None, LinkGeomSource.NormalLinkInterface, None, None))
    result should be(Set(expectedConversionResult))
  }

  test("Report parse error from time domain parsing") {
    val segment = prohibitionSegment(validityPeriod = Some("[[(h8){h7"))
    val prohibitionSegments = Seq(segment)
    val roadLink = VVHRoadlink(1l, 235, Seq(Point(0.0, 0.0), Point(1.0, 0.0)), Municipality, TrafficDirection.BothDirections, FeatureClass.AllOthers, attributes = CommonAttributes)
    val roadLinks: Seq[VVHRoadlink] = Seq(roadLink)

    val result: Set[Either[String, PersistedLinearAsset]] = assetDataImporter.convertToProhibitions(prohibitionSegments, roadLinks, Nil).toSet

    val expectedConversionError = Left("Parsing time domain string [[(h8){h7 failed with message: end of input. Dropped prohibition 1.")
    result should be(Set(expectedConversionError))
  }

  case class LinearAssetSegment(linkId: Option[Long], startMeasure: Double, endMeasure: Double)

  private def insertSpeedLimitValue(assetId: Long, value: Int): Unit = {
    val propertyId = StaticQuery.query[String, Long](Queries.propertyIdByPublicId).apply("rajoitus").first

    sqlu"""
      insert into single_choice_value(asset_id, enumerated_value_id, property_id, modified_date)
      values ($assetId, (select id from enumerated_value where property_id = $propertyId and value = $value), $propertyId, SYSDATE)
      """.execute
  }

  private def insertNumericalLimitValue(assetId: Long, value: Int): Unit = {
    val numberPropertyValueId = Sequences.nextPrimaryKeySeqValue
    val propertyId = StaticQuery.query[String, Long](Queries.propertyIdByPublicId).apply("mittarajoitus").first

    sqlu"""
      insert into number_property_value(id, asset_id, property_id, value)
      values ($numberPropertyValueId, $assetId, $propertyId, $value)
      """.execute
  }

  private def fetchNumericalLimitSegments(creator: String): List[(Long, Long, Long, Double, Double, Option[Int], Boolean, Option[DateTime], String, Option[DateTime])] = {
    sql"""
        select a.id, lrm.id, lrm.link_id, lrm.start_measure, lrm.end_measure,
               n.value, a.floating, a.valid_to, a.modified_by, a.modified_date
        from asset a
        join asset_link al on al.asset_id = a.id
        join lrm_position lrm on lrm.id = al.position_id
        left join number_property_value n on a.id = n.asset_id
        where a.created_by = $creator
      """.as[(Long, Long, Long, Double, Double, Option[Int], Boolean, Option[DateTime], String, Option[DateTime])].list
  }

  private def fetchSpeedLimitSegments(creator: String): List[(Long, Long, Long, Double, Double, Int, Boolean)] = {
    sql"""
        select a.id, lrm.id, lrm.link_id, lrm.start_measure, lrm.end_measure, e.value, a.floating
        from asset a
        join asset_link al on al.asset_id = a.id
        join lrm_position lrm on lrm.id = al.position_id
        join single_choice_value s on a.id = s.asset_id
        join enumerated_value e on e.id = s.enumerated_value_id
        where a.created_by = $creator
      """.as[(Long, Long, Long, Double, Double, Int, Boolean)].list
  }

  private def getDateTimeNowFromDatabase() ={
    sql"""
          select SYSTIMESTAMP from dual
      """.as[(DateTime)].list
  }
}

package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.asset.SideCode.{AgainstDigitizing, TowardsDigitizing}
import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset.{LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.client.vvh.{ChangeInfo, ChangeType}
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.dao.Discontinuity.EndOfRoad
import fi.liikennevirasto.viite.dao.FloatingReason.NoFloating
import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination
import fi.liikennevirasto.viite.{LinkRoadAddressHistory, NewRoadway, RoadType}
import fi.liikennevirasto.viite.dao.{Discontinuity, FloatingReason, RoadAddress}
import org.joda.time.DateTime
import org.scalatest.{FunSuite, Matchers}


class RoadwayChangeInfoMapperSpec extends FunSuite with Matchers {

  //TODO the road address now have the linear location id and has been set to 1L
  val roadAddr = RoadAddress(1, 1L, 1, 1, RoadType.Unknown, Track.RightSide, Discontinuity.Continuous, 0, 1000, Some(DateTime.now), None,
    None, 0L, 0.0, 1000.0, SideCode.AgainstDigitizing, 0, (None, None), NoFloating, Seq(Point(0.0, 0.0), Point(1000.234, 0.0)),
    LinkGeomSource.NormalLinkInterface, 8, NoTermination, 123456)

  //TODO will be implemented at VIITE-1536
//  test("resolve simple case") {
//
//    val roadAddress1 = roadAddr.copy(startAddrMValue = 0, endAddrMValue = 1000, linkId = 123L, endMValue = 1000.234, geometry = Seq(Point(0.0, 0.0), Point(1000.234, 0.0)))
//    val roadAddress2 = roadAddr.copy(startAddrMValue = 1000, endAddrMValue = 1400, linkId = 124L, endMValue = 399.648, geometry = Seq(Point(1000.234, 0.0), Point(1000.234, 399.648)))
//    val map = Seq(roadAddress1, roadAddress2).groupBy(ad => (ad.linkId, ad.roadwayNumber)).mapValues(s => LinkRoadAddressHistory(s, Seq()))
//    val changes = Seq(
//      ChangeInfo(Some(123), Some(124), 123L, ChangeType.CombinedRemovedPart, Some(0.0), Some(1000.234), Some(399.648), Some(1399.882), 96400L),
//      ChangeInfo(Some(124), Some(124), 123L, ChangeType.CombinedModifiedPart, Some(0.0), Some(399.648), Some(0.0), Some(399.648), 96400L))
//    val results = RoadAddressChangeInfoMapper.resolveChangesToMap(map, changes).mapValues(_.allSegments)
//    results.get(123).isEmpty should be (true)
//    results.get(124).isEmpty should be (false)
//    results(124).size should be (2)
//    results.values.flatten.exists(_.startAddrMValue == 0) should be (true)
//    results.values.flatten.exists(_.startAddrMValue == 1000) should be (true)
//    results.values.flatten.exists(_.endAddrMValue == 1000) should be (true)
//    results.values.flatten.exists(_.endAddrMValue == 1400) should be (true)
//    results.values.flatten.forall(_.adjustedTimestamp == 96400L) should be (true)
//    results.values.flatten.map(_.roadwayNumber).toSet.size should be (1)
//    results.values.flatten.map(_.roadwayNumber).toSet.head should be (roadAddress1.roadwayNumber)
//  }

  //TODO will be implemented at VIITE-1536
//  test("transfer 1 to 2, modify 2, then transfer 2 to 3") {
//    val roadLinkId1 = 123L
//    val roadLinkId2 = 456L
//    val roadLinkId3 = 789L
//
//    val roadAdjustedTimestamp = 0L
//    val changesVVHTimestamp = 96400L
//
//    val roadAddress1 = roadAddr.copy(startAddrMValue = 0, endAddrMValue = 1000, linkId = roadLinkId1, endMValue = 1000.234, adjustedTimestamp = roadAdjustedTimestamp, geometry = Seq(Point(0.0, 0.0), Point(1000.234, 0.0)))
//    val roadAddress2 = roadAddr.copy(startAddrMValue = 1000, endAddrMValue = 1400, linkId = roadLinkId2, endMValue = 399.648, adjustedTimestamp = roadAdjustedTimestamp, geometry = Seq(Point(1000.234, 0.0), Point(1000.234, 399.648)))
//    val map = Seq(roadAddress1, roadAddress2).groupBy(ad => (ad.linkId, ad.roadwayNumber)).mapValues(s => LinkRoadAddressHistory(s, Seq()))
//    val changes = Seq(
//      ChangeInfo(Some(roadLinkId1), Some(roadLinkId2), 123L, 2, Some(0.0), Some(1000.234), Some(399.648), Some(1399.882), changesVVHTimestamp),
//      ChangeInfo(Some(roadLinkId2), Some(roadLinkId2), 123L, 1, Some(0.0), Some(399.648), Some(0.0), Some(399.648), changesVVHTimestamp),
//      ChangeInfo(Some(roadLinkId2), Some(roadLinkId3), 123L, 2, Some(0.0), Some(1399.882), Some(1399.882), Some(0.0), changesVVHTimestamp + 1L)
//    )
//    val results = RoadAddressChangeInfoMapper.resolveChangesToMap(map, changes).mapValues(_.allSegments)
//    results.get(roadLinkId1).isEmpty should be (true)
//    results.get(roadLinkId2).isEmpty should be (true)
//    results.get(roadLinkId3).isEmpty should be (false)
//    results(roadLinkId3).size should be (2)
//    results(roadLinkId3).count(_.id == -1000) should be (2)
//    results(roadLinkId3).count(rl => rl.id == -1000 && (rl.startAddrMValue == 0 || rl.startAddrMValue == 1000) && (rl.endAddrMValue == 1000 || rl.endAddrMValue == 1400)) should be (2)
//    results.values.flatten.exists(_.startAddrMValue == 0) should be (true)
//    results.values.flatten.exists(_.startAddrMValue == 1000) should be (true)
//    results.values.flatten.exists(_.endAddrMValue == 1000) should be (true)
//    results.values.flatten.exists(_.endAddrMValue == 1400) should be (true)
//    results.values.flatten.map(_.roadwayNumber).toSet.size should be (1)
//    results.values.flatten.map(_.roadwayNumber).toSet.head should be (roadAddress1.roadwayNumber)
//  }

  //TODO will be implemented at VIITE-1536
//  test("no changes should apply") {
//    val roadLinkId1 = 123L
//    val roadLinkId2 = 456L
//    val roadLinkId3 = 789L
//
//    val roadAdjustedTimestamp = 964000L
//    val changesVVHTimestamp = 96400L
//
//    val roadAddress1 = roadAddr.copy(startAddrMValue = 0, endAddrMValue = 1000, linkId = roadLinkId1, endMValue = 1000.234,
//      adjustedTimestamp = roadAdjustedTimestamp, geometry = Seq(Point(0.0, 0.0), Point(1000.234, 0.0)))
//    val roadAddress2 = roadAddr.copy(startAddrMValue = 1000, endAddrMValue = 1400, linkId = roadLinkId2, endMValue = 399.648,
//      adjustedTimestamp = roadAdjustedTimestamp, geometry = Seq(Point(1000.234, 0.0), Point(1000.234, 399.648)))
//    val roadAddress3 = roadAddr.copy(roadNumber = 75, roadPartNumber = 2, track = Track.Combined, startAddrMValue = 3532, endAddrMValue = 3598,
//      createdBy = Some("tr"), linkId = roadLinkId3, endMValue = 65.259, adjustedTimestamp = roadAdjustedTimestamp,
//      floating = FloatingReason.ApplyChanges, geometry = List(Point(538889.668, 6999800.979, 0.0), Point(538912.266, 6999862.199, 0.0)))
//    val map = Seq(roadAddress1, roadAddress2, roadAddress3).groupBy(ad => (ad.linkId, ad.roadwayNumber)).mapValues(s => LinkRoadAddressHistory(s, Seq()))
//    val changes = Seq(
//      ChangeInfo(Some(roadLinkId1), Some(roadLinkId2), 123L, 2, Some(0.0), Some(1000.234), Some(399.648), Some(1399.882), changesVVHTimestamp),
//      ChangeInfo(Some(roadLinkId2), Some(roadLinkId2), 123L, 1, Some(0.0), Some(399.648), Some(0.0), Some(399.648), changesVVHTimestamp),
//      ChangeInfo(Some(roadLinkId2), Some(roadLinkId3), 123L, 2, Some(0.0), Some(6666), Some(200), Some(590), changesVVHTimestamp)
//    )
//    val results = RoadAddressChangeInfoMapper.resolveChangesToMap(map, changes).mapValues(_.allSegments)
//    results.get(roadLinkId1).isEmpty should be (false)
//    results.get(roadLinkId2).isEmpty should be (false)
//    results.get(roadLinkId3).isEmpty should be (false)
//    results(roadLinkId1).size should be (1)
//    results(roadLinkId1).count(_.id == -1000) should be (0)
//    results(roadLinkId1).head.eq(roadAddress1) should be (true)
//    results(roadLinkId2).size should be (1)
//    results(roadLinkId2).count(_.id == -1000) should be (0)
//    results(roadLinkId2).head.eq(roadAddress2) should be (true)
//    results(roadLinkId3).size should be (1)
//    results(roadLinkId3).count(_.id == -1000) should be (0)
//    results(roadLinkId3).head.eq(roadAddress3) should be (true)
//    results.values.flatten.map(_.roadwayNumber).toSet.size should be (1)
//    results.values.flatten.map(_.roadwayNumber).toSet.head should be (roadAddress1.roadwayNumber)
//  }
//
//  test("split a road address link into three") {
//    val roadLinkId1 = 123L
//    val roadLinkId2 = 456L
//    val roadLinkId3 = 789L
//
//    val changesVVHTimestamp = 96400L
//
//    val roadAddress1 = roadAddr.copy(discontinuity = Discontinuity.EndOfRoad, startAddrMValue = 400, endAddrMValue = 1400, linkId = roadLinkId1, endMValue = 960.434, geometry = Seq(Point(0.0, 0.0), Point(960.434, 0.0)))
//    val map = Seq(roadAddress1).groupBy(ad => (ad.linkId, ad.roadwayNumber)).mapValues(s => LinkRoadAddressHistory(s, Seq()))
//    val changes = Seq(
//      //Remain
//      ChangeInfo(Some(roadLinkId1), Some(roadLinkId1), 123L, 5, Some(399.648), Some(847.331), Some(0.0), Some(447.682), changesVVHTimestamp),
//      //Move
//      ChangeInfo(Some(roadLinkId1), Some(roadLinkId2), 456L, 6, Some(0.0), Some(399.648), Some(0.0), Some(399.648), changesVVHTimestamp),
//      ChangeInfo(Some(roadLinkId1), Some(roadLinkId3), 789L, 6, Some(847.331), Some(960.434), Some(113.103), Some(0.0), changesVVHTimestamp)
//    )
//    val results = RoadAddressChangeInfoMapper.resolveChangesToMap(map, changes).mapValues(_.allSegments.sortBy(_.startAddrMValue))
//    results.get(roadLinkId1).isEmpty should be (false)
//    results.get(roadLinkId2).isEmpty should be (false)
//    results.get(roadLinkId3).isEmpty should be (false)
//    val addr1 = results(roadLinkId1).head
//    addr1.startMValue should be (0.0)
//    addr1.endMValue should be (447.682)
//    addr1.startAddrMValue should be (518)
//    addr1.endAddrMValue should be (984)
//    addr1.adjustedTimestamp should be (changesVVHTimestamp)
//    addr1.sideCode should be (AgainstDigitizing)
//    val addr2 = results(roadLinkId2).head
//    addr2.startMValue should be (0.0)
//    addr2.endMValue should be (399.648)
//    addr2.startAddrMValue should be (984)
//    addr2.endAddrMValue should be (1400)
//    addr2.adjustedTimestamp should be (changesVVHTimestamp)
//    addr2.sideCode should be (AgainstDigitizing)
//    val addr3 = results(roadLinkId3).head
//    addr3.startMValue should be (0.0)
//    addr3.endMValue should be (113.103)
//    addr3.startAddrMValue should be (400)
//    addr3.endAddrMValue should be (518)
//    addr3.adjustedTimestamp should be (changesVVHTimestamp)
//    addr3.sideCode should be (TowardsDigitizing)
//    addr3.discontinuity should be (EndOfRoad)
//    results.values.flatten.map(_.roadwayNumber).toSet.size should be (1)
//    results.values.flatten.map(_.roadwayNumber).toSet.head should be (roadAddress1.roadwayNumber)
//  }
//
//  test("Lengthened road links") {
//    val roadLinkId1 = 123L
//    val roadLinkId2 = 456L
//    val roadLinkId3 = 789L
//
//    val roadAdjustedTimestamp = 0L
//    val changesVVHTimestamp = 96400L
//
//    val roadAddress1 = roadAddr.copy(startAddrMValue = 400, endAddrMValue = 1400, linkId = roadLinkId1, endMValue = 960.434, geometry = Seq(Point(0.0, 0.0), Point(960.434, 0.0)), adjustedTimestamp = roadAdjustedTimestamp)
//    val roadAddress2 = roadAddr.copy(startAddrMValue = 1400, endAddrMValue = 1600, linkId = roadLinkId2, endMValue = 201.333, sideCode = SideCode.TowardsDigitizing, geometry = Seq(Point(0.0, 0.0), Point(0.0, 201.333)), adjustedTimestamp = roadAdjustedTimestamp)
//    val roadAddress3 = roadAddr.copy(startAddrMValue = 1600, endAddrMValue =  1610, linkId = roadLinkId3, endMValue = 20.0, sideCode = SideCode.TowardsDigitizing, geometry = Seq(Point(0.0, 201.333), Point(0.0, 231.333)), adjustedTimestamp = roadAdjustedTimestamp)
//    val map = Seq(roadAddress1, roadAddress2, roadAddress3).groupBy(ad => (ad.linkId, ad.roadwayNumber)).mapValues(s => LinkRoadAddressHistory(s, Seq()))
//    val changes = Seq(
//      //Old parts
//      ChangeInfo(Some(roadLinkId1), Some(roadLinkId1), 123L, 3, Some(0.0), Some(960.434), Some(0.535), Some(960.969), changesVVHTimestamp),
//      ChangeInfo(Some(roadLinkId2), Some(roadLinkId2), 456L, 3, Some(0.0), Some(201.333), Some(201.333), Some(0.0), changesVVHTimestamp),
//      ChangeInfo(Some(roadLinkId3), Some(roadLinkId3), 757L, 3, Some(0.0), Some(10.0), Some(0.0), Some(10.0), changesVVHTimestamp),
//      //New parts
//      ChangeInfo(None, Some(roadLinkId1), 123L, 4, None, None, Some(0.0), Some(0.535), changesVVHTimestamp),
//      ChangeInfo(None, Some(roadLinkId2), 456L, 4, None, None, Some(201.333), Some(201.986), changesVVHTimestamp),
//      ChangeInfo(None, Some(roadLinkId3), 757L, 4, None, None, Some(10.0), Some(31.001), changesVVHTimestamp)
//    )
//    val results = RoadAddressChangeInfoMapper.resolveChangesToMap(map, changes).mapValues(_.allSegments.sortBy(_.startAddrMValue))
//    results.get(roadLinkId1).isEmpty should be (false)
//    results.get(roadLinkId2).isEmpty should be (false)
//    results.get(roadLinkId3).isEmpty should be (false)
//    val addr1 = results(roadLinkId1).head
//    addr1.startMValue should be (0.0)
//    addr1.endMValue should be (960.969)
//    addr1.startAddrMValue should be (400)
//    addr1.endAddrMValue should be (1400)
//    addr1.isFloating should be (false)
//    addr1.adjustedTimestamp should be (changesVVHTimestamp)
//    addr1.sideCode should be (AgainstDigitizing)
//    val addr2 = results(roadLinkId2).head
//    addr2.startMValue should be (0.0)
//    addr2.endMValue should be (201.986)
//    addr2.startAddrMValue should be (1400)
//    addr2.endAddrMValue should be (1600)
//    addr2.isFloating should be (false)
//    addr2.adjustedTimestamp should be (changesVVHTimestamp)
//    addr2.sideCode should be (AgainstDigitizing)
//    val addr3 = results(roadLinkId3).head
//    addr3.startMValue should be (0.0)
//    addr3.endMValue should be (20.0)
//    addr3.startAddrMValue should be (1600)
//    addr3.endAddrMValue should be (1610)
//    addr3.isFloating should be (true)
//    addr3.adjustedTimestamp should be (roadAdjustedTimestamp)
//    addr3.sideCode should be (TowardsDigitizing)
//    results.values.flatten.map(_.roadwayNumber).toSet.size should be (1)
//    results.values.flatten.map(_.roadwayNumber).toSet.head should be (roadAddress1.roadwayNumber)
//  }
//
//  test("Lengthened changes with multiple road addresses at same road link") {
//    val roadLinkId1 = 123L
//    val roadLinkId2 = 456L
//    val roadLinkId3 = 789L
//
//    val roadAdjustedTimestamp = 0L
//    val changesVVHTimestamp = 96400L
//
//    val roadAddress1 = roadAddr.copy(startAddrMValue = 400, endAddrMValue = 800, linkId = roadLinkId1, endMValue = 400.0, geometry = Seq(Point(0.0, 0.0), Point(400.0, 0.0)), adjustedTimestamp = roadAdjustedTimestamp)
//    val roadAddress2 = roadAddr.copy(startAddrMValue = 800, endAddrMValue = 1400, linkId = roadLinkId1, startMValue = 400.0, endMValue = 960.434, geometry = Seq(Point(400.0, 0.0), Point(960.434, 0.0)), adjustedTimestamp = roadAdjustedTimestamp)
//    val roadAddress3 = roadAddr.copy(startAddrMValue = 1400, endAddrMValue = 1600, linkId = roadLinkId2, endMValue = 201.333, sideCode = SideCode.TowardsDigitizing, geometry = Seq(Point(0.0, 0.0), Point(0.0, 201.333)), adjustedTimestamp = roadAdjustedTimestamp)
//    val roadAddress4 = roadAddr.copy(startAddrMValue = 1600, endAddrMValue =  1610, linkId = roadLinkId3, endMValue = 20.0, sideCode = SideCode.TowardsDigitizing, geometry = Seq(Point(0.0, 201.333), Point(0.0, 231.333)), adjustedTimestamp = roadAdjustedTimestamp)
//    val map = Seq(roadAddress1, roadAddress2, roadAddress3, roadAddress4).groupBy(ad => (ad.linkId, ad.roadwayNumber)).mapValues(s => LinkRoadAddressHistory(s, Seq()))
//    val changes = Seq(
//      //Old parts
//      ChangeInfo(Some(roadLinkId1), Some(roadLinkId1), 123L, 3, Some(0.0), Some(960.434), Some(0.535), Some(960.969), changesVVHTimestamp),
//      ChangeInfo(Some(roadLinkId2), Some(roadLinkId2), 456L, 3, Some(0.0), Some(201.333), Some(201.333), Some(0.0), changesVVHTimestamp),
//      ChangeInfo(Some(roadLinkId3), Some(roadLinkId3), 757L, 3, Some(0.0), Some(10.0), Some(0.0), Some(10.0), changesVVHTimestamp),
//      //New parts
//      ChangeInfo(None, Some(roadLinkId1), 123L, 4, None, None, Some(0.0), Some(0.535), changesVVHTimestamp),
//      ChangeInfo(None, Some(roadLinkId2), 456L, 4, None, None, Some(201.333), Some(201.986), changesVVHTimestamp),
//      ChangeInfo(None, Some(roadLinkId3), 757L, 4, None, None, Some(10.0), Some(31.001), changesVVHTimestamp)
//    )
//    val results = RoadAddressChangeInfoMapper.resolveChangesToMap(map, changes).mapValues(_.allSegments.sortBy(_.startAddrMValue))
//    results.get(roadLinkId1).isEmpty should be (false)
//    results.get(roadLinkId2).isEmpty should be (false)
//    results.get(roadLinkId3).isEmpty should be (false)
//    val addrAtLink1 = results(roadLinkId1).sortBy(_.startAddrMValue)
//    val addr1 = addrAtLink1.head
//    addr1.startMValue should be (0.0)
//    addr1.endMValue should be (400.222 +- 0.001)
//    addr1.startAddrMValue should be (400)
//    addr1.endAddrMValue should be (800)
//    addr1.isFloating should be (false)
//    addr1.adjustedTimestamp should be (changesVVHTimestamp)
//    addr1.sideCode should be (AgainstDigitizing)
//
//    val addr2 = addrAtLink1.last
//    addr2.startMValue should be (400.222 +- 0.001)
//    addr2.endMValue should be (960.969)
//    addr2.startAddrMValue should be (800)
//    addr2.endAddrMValue should be (1400)
//    addr2.isFloating should be (false)
//    addr2.adjustedTimestamp should be (changesVVHTimestamp)
//    addr2.sideCode should be (AgainstDigitizing)
//
//    val addr3 = results(roadLinkId2).head
//    addr3.startMValue should be (0.0)
//    addr3.endMValue should be (201.986)
//    addr3.startAddrMValue should be (1400)
//    addr3.endAddrMValue should be (1600)
//    addr3.isFloating should be (false)
//    addr3.adjustedTimestamp should be (changesVVHTimestamp)
//    addr3.sideCode should be (AgainstDigitizing)
//
//    val addr4 = results(roadLinkId3).head
//    addr4.startMValue should be (0.0)
//    addr4.endMValue should be (20.0)
//    addr4.startAddrMValue should be (1600)
//    addr4.endAddrMValue should be (1610)
//    addr4.isFloating should be (true)
//    addr4.adjustedTimestamp should be (roadAdjustedTimestamp)
//    addr4.sideCode should be (TowardsDigitizing)
//    results.values.flatten.map(_.roadwayNumber).toSet.size should be (1)
//    results.values.flatten.map(_.roadwayNumber).toSet.head should be (roadAddress1.roadwayNumber)
//  }
//
//  test("Shortened road links") {
//    val roadLinkId1 = 123L
//    val roadLinkId2 = 456L
//    val roadLinkId3 = 789L
//
//    val roadAdjustedTimestamp = 0L
//    val changesVVHTimestamp = 96400L
//
//    val roadAddress1 = roadAddr.copy(startAddrMValue = 400, endAddrMValue = 1400, linkId = roadLinkId1, endMValue = 960.969, geometry = Seq(Point(0.0, 0.0), Point(960.969, 0.0)), adjustedTimestamp = roadAdjustedTimestamp, track = Track.Combined)
//    val roadAddress2 = roadAddr.copy(startAddrMValue = 1400, endAddrMValue = 1600, linkId = roadLinkId2, endMValue = 201.986, sideCode = SideCode.TowardsDigitizing, geometry = Seq(Point(0.0, 0.0), Point(0.0, 201.986)), adjustedTimestamp = roadAdjustedTimestamp, track = Track.Combined)
//    val roadAddress3 = roadAddr.copy(startAddrMValue = 1600, endAddrMValue = 1610, linkId = roadLinkId3, endMValue = 31.001, sideCode = SideCode.TowardsDigitizing, geometry = Seq(Point(0.0, 201.333), Point(0.0, 232.334)), adjustedTimestamp = roadAdjustedTimestamp, track = Track.Combined)
//    val map = Seq(roadAddress1, roadAddress2, roadAddress3).groupBy(ad => (ad.linkId, ad.roadwayNumber)).mapValues(s => LinkRoadAddressHistory(s, Seq()))
//    val changes = Seq(
//      //common parts
//      ChangeInfo(Some(roadLinkId1), Some(roadLinkId1), 123L, 7, Some(0.535), Some(960.969), Some(0.0), Some(960.434), changesVVHTimestamp),
//      ChangeInfo(Some(roadLinkId2), Some(roadLinkId2), 456L, 7, Some(201.986), Some(0.0), Some(0.0), Some(201.986), changesVVHTimestamp),
//      ChangeInfo(Some(roadLinkId3), Some(roadLinkId3), 757L, 7, Some(0.0), Some(10.0), Some(0.0), Some(10.0), changesVVHTimestamp),
//      //removed parts
//      ChangeInfo(Some(roadLinkId1), None, 123L, 8, Some(0.0), Some(0.535), None, None, changesVVHTimestamp),
//      ChangeInfo(Some(roadLinkId2), None, 456L, 8, Some(201.986), Some(201.986), None, None, changesVVHTimestamp),
//      ChangeInfo(Some(roadLinkId3), None, 757L, 8, Some(10.0), Some(31.001), None, None, changesVVHTimestamp)
//    )
//    val results = RoadAddressChangeInfoMapper.resolveChangesToMap(map, changes).mapValues(_.allSegments.sortBy(_.startAddrMValue))
//    results.get(roadLinkId1).isEmpty should be (false)
//    results.get(roadLinkId2).isEmpty should be (false)
//    results.get(roadLinkId3).isEmpty should be (false)
//    val addr1 = results(roadLinkId1).head
//    addr1.startMValue should be (0.0)
//    addr1.endMValue should be (960.434)
//    addr1.startAddrMValue should be (400)
//    addr1.endAddrMValue should be (1400)
//    addr1.isFloating should be (false)
//    addr1.adjustedTimestamp should be (changesVVHTimestamp)
//    addr1.sideCode should be (AgainstDigitizing)
//    val addr2 = results(roadLinkId2).head
//    addr2.startMValue should be (0.0)
//    addr2.endMValue should be (201.986)
//    addr2.startAddrMValue should be (1400)
//    addr2.endAddrMValue should be (1600)
//    addr2.isFloating should be (false)
//    addr2.adjustedTimestamp should be (changesVVHTimestamp)
//    addr2.sideCode should be (AgainstDigitizing)
//    val addr3 = results(roadLinkId3).head
//    addr3.id shouldNot be (NewRoadway)
//    addr3.startMValue should be (0.0)
//    addr3.endMValue should be (31.001)
//    addr3.startAddrMValue should be (1600)
//    addr3.endAddrMValue should be (1610)
//    addr3.isFloating should be (true)
//    addr3.adjustedTimestamp should be (roadAdjustedTimestamp)
//    addr3.sideCode should be (TowardsDigitizing)
//    results.values.flatten.map(_.roadwayNumber).toSet.size should be (1)
//    results.values.flatten.map(_.roadwayNumber).toSet.head should be (roadAddress1.roadwayNumber)
//  }
//
//  test("Shortened changes with multiple road addresses at same road link") {
//    val roadLinkId1 = 123L
//    val roadLinkId2 = 456L
//    val roadLinkId3 = 789L
//
//    val roadAdjustedTimestamp = 0L
//    val changesVVHTimestamp = 96400L
//
//    val roadAddress1 = roadAddr.copy(startAddrMValue = 400, endAddrMValue = 800, linkId = roadLinkId1, endMValue = 400.0, geometry = Seq(Point(0.0, 0.0), Point(400.0, 0.0)), adjustedTimestamp = roadAdjustedTimestamp, track = Track.Combined)
//    val roadAddress2 = roadAddr.copy(startAddrMValue = 800, endAddrMValue = 1400, linkId = roadLinkId1, startMValue = 400.0, endMValue = 960.969, geometry = Seq(Point(400.0, 0.0), Point(960.969, 0.0)), adjustedTimestamp = roadAdjustedTimestamp, track = Track.Combined)
//    val roadAddress3 = roadAddr.copy(startAddrMValue = 1400, endAddrMValue = 1600, linkId = roadLinkId2, endMValue = 201.986, sideCode = SideCode.TowardsDigitizing, geometry = Seq(Point(0.0, 0.0), Point(0.0, 201.986)), adjustedTimestamp = roadAdjustedTimestamp, track = Track.Combined)
//    val roadAddress4 = roadAddr.copy(startAddrMValue = 1600, endAddrMValue = 1610, linkId = roadLinkId3, endMValue = 31.001, sideCode = SideCode.TowardsDigitizing, geometry = Seq(Point(0.0, 201.333), Point(0.0, 232.334)), adjustedTimestamp = roadAdjustedTimestamp, track = Track.Combined)
//    val map = Seq(roadAddress1,roadAddress2, roadAddress3, roadAddress4).groupBy(ad => (ad.linkId, ad.roadwayNumber)).mapValues(s => LinkRoadAddressHistory(s, Seq()))
//    val changes = Seq(
//      //common parts
//      ChangeInfo(Some(roadLinkId1), Some(roadLinkId1), 123L, 7, Some(0.535), Some(960.969), Some(0.0), Some(960.434), changesVVHTimestamp),
//      ChangeInfo(Some(roadLinkId2), Some(roadLinkId2), 456L, 7, Some(201.986), Some(0.0), Some(0.0), Some(201.986), changesVVHTimestamp),
//      ChangeInfo(Some(roadLinkId3), Some(roadLinkId3), 757L, 7, Some(0.0), Some(10.0), Some(0.0), Some(10.0), changesVVHTimestamp),
//      //removed parts
//      ChangeInfo(Some(roadLinkId1), None, 123L, 8, Some(0.0), Some(0.535), None, None, changesVVHTimestamp),
//      ChangeInfo(Some(roadLinkId2), None, 456L, 8, Some(201.986), Some(201.986), None, None, changesVVHTimestamp),
//      ChangeInfo(Some(roadLinkId3), None, 757L, 8, Some(10.0), Some(31.001), None, None, changesVVHTimestamp)
//    )
//    val results = RoadAddressChangeInfoMapper.resolveChangesToMap(map, changes).mapValues(_.allSegments.sortBy(_.startAddrMValue))
//    results.get(roadLinkId1).isEmpty should be (false)
//    results.get(roadLinkId2).isEmpty should be (false)
//    results.get(roadLinkId3).isEmpty should be (false)
//    val addrAtLink1 = results(roadLinkId1).sortBy(_.startAddrMValue)
//    val addr1 = addrAtLink1.head
//    addr1.startMValue should be (0.0)
//    addr1.endMValue should be (399.777 +- 0.001)
//    addr1.startAddrMValue should be (400)
//    addr1.endAddrMValue should be (800)
//    addr1.isFloating should be (false)
//    addr1.adjustedTimestamp should be (changesVVHTimestamp)
//    addr1.sideCode should be (AgainstDigitizing)
//
//    val addr2 = addrAtLink1.last
//    addr2.startMValue should be (399.777 +- 0.001)
//    addr2.endMValue should be (960.434)
//    addr2.startAddrMValue should be (800)
//    addr2.endAddrMValue should be (1400)
//    addr2.isFloating should be (false)
//    addr2.adjustedTimestamp should be (changesVVHTimestamp)
//    addr2.sideCode should be (AgainstDigitizing)
//
//    val addr3 = results(roadLinkId2).head
//    addr3.startMValue should be (0.0)
//    addr3.endMValue should be (201.986)
//    addr3.startAddrMValue should be (1400)
//    addr3.endAddrMValue should be (1600)
//    addr3.isFloating should be (false)
//    addr3.adjustedTimestamp should be (changesVVHTimestamp)
//    addr3.sideCode should be (AgainstDigitizing)
//    val addr4 = results(roadLinkId3).head
//    addr4.id shouldNot be (NewRoadway)
//    addr4.startMValue should be (0.0)
//    addr4.endMValue should be (31.001)
//    addr4.startAddrMValue should be (1600)
//    addr4.endAddrMValue should be (1610)
//    addr4.isFloating should be (true)
//    addr4.adjustedTimestamp should be (roadAdjustedTimestamp)
//    addr4.sideCode should be (TowardsDigitizing)
//    results.values.flatten.map(_.roadwayNumber).toSet.size should be (1)
//    results.values.flatten.map(_.roadwayNumber).toSet.head should be (roadAddress1.roadwayNumber)
//  }
//
//  test("Removed link") {
//    val roadLinkId1 = 123L
//
//    val roadAdjustedTimestamp = 0L
//    val changesVVHTimestamp = 96400L
//
//    val roadAddress1 = roadAddr.copy(startAddrMValue = 400, endAddrMValue = 1400, linkId = roadLinkId1, endMValue = 960.434, roadwayNumber = 0,
//      geometry = Seq(Point(0.0, 0.0), Point(960.434, 0.0)), adjustedTimestamp = roadAdjustedTimestamp, track = Track.Combined)
//    val map = Seq(roadAddress1).groupBy(ad => (ad.linkId, ad.roadwayNumber)).mapValues(s => LinkRoadAddressHistory(s, Seq()))
//    val changes = Seq(ChangeInfo(Some(roadLinkId1), Some(roadLinkId1), 123L, 11, Some(0.0), Some(960.434), None, None, changesVVHTimestamp))
//    val results = RoadAddressChangeInfoMapper.resolveChangesToMap(map, changes).mapValues(_.allSegments.sortBy(_.startAddrMValue))
//    results.get(roadLinkId1).isEmpty should be (false)
//    val addr1 = results(roadLinkId1).head
//    addr1.startMValue should be (0.0)
//    addr1.endMValue should be (960.434)
//    addr1.startAddrMValue should be (400)
//    addr1.endAddrMValue should be (1400)
//    addr1.isFloating should be (true)
//    addr1.adjustedTimestamp should be (roadAdjustedTimestamp)
//    addr1.sideCode should be (AgainstDigitizing)
//    results.values.flatten.map(_.roadwayNumber).toSet.size should be (1)
//    results.values.flatten.map(_.roadwayNumber).toSet.head should be (roadAddress1.roadwayNumber)
//  }
//
//  test("Multiple operations applied in order") {
//    val roadLinkId1 = 123L
//    val roadLinkId2 = 456L
//
//    val roadAdjustedTimestamp = 0L
//    val changesVVHTimestamp1 = 96400L
//    val changesVVHTimestamp2 = 3 * 96400L
//
//    val roadAddress1 = roadAddr.copy(track = Track.Combined, startAddrMValue = 400, endAddrMValue = 1400, linkId = roadLinkId1, endMValue = 960.434,
//      adjustedTimestamp = roadAdjustedTimestamp, geometry = Seq(Point(0.0, 0.0), Point(960.434, 0.0)), roadwayNumber = 0)
//    val map = Seq(roadAddress1).groupBy(ad => (ad.linkId, ad.roadwayNumber)).mapValues(s => LinkRoadAddressHistory(s, Seq()))
//    val changes = Seq(
//      // Extend and turn digitization direction around
//      ChangeInfo(Some(roadLinkId1), Some(roadLinkId1), 123L, 3, Some(0.0), Some(960.434), Some(960.969), Some(0.535), changesVVHTimestamp1),
//      ChangeInfo(Some(roadLinkId1), Some(roadLinkId1), 123L, 4, None, None, Some(0.0), Some(0.535), changesVVHTimestamp1),
//      //Remain
//      ChangeInfo(Some(roadLinkId1), Some(roadLinkId1), 123L, 5, Some(399.648), Some(960.969), Some(0.0), Some(960.969-399.648), changesVVHTimestamp2),
//      //Move and turn around
//      ChangeInfo(Some(roadLinkId1), Some(roadLinkId2), 456L, 6, Some(0.0), Some(399.648), Some(399.648), Some(0.0), changesVVHTimestamp2)
//    )
//    val results = RoadAddressChangeInfoMapper.resolveChangesToMap(map, changes).mapValues(_.allSegments.sortBy(_.startAddrMValue))
//    results.get(roadLinkId1).isEmpty should be (false)
//    results.get(roadLinkId2).isEmpty should be (false)
//    val addr1 = results(roadLinkId1).head
//    addr1.startMValue should be (0.0)
//    addr1.endMValue should be (960.969-399.648 +- 0.001)
//    addr1.startAddrMValue should be (816)
//    addr1.endAddrMValue should be (1400)
//    addr1.isFloating should be (false)
//    addr1.adjustedTimestamp should be (changesVVHTimestamp2)
//    addr1.sideCode should be (TowardsDigitizing)
//    val addr2 = results(roadLinkId2).head
//    addr2.startMValue should be (0.0)
//    addr2.endMValue should be (399.648)
//    addr2.startAddrMValue should be (400)
//    addr2.endAddrMValue should be (addr1.startAddrMValue)
//    addr2.isFloating should be (false)
//    addr2.adjustedTimestamp should be (changesVVHTimestamp2)
//    addr2.sideCode should be (AgainstDigitizing)
//    results.values.flatten.map(_.roadwayNumber).toSet.size should be (1)
//    results.values.flatten.map(_.roadwayNumber).toSet.head should be (roadAddress1.roadwayNumber)
//  }
}

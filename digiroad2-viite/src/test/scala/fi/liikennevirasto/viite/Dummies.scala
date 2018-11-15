package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset.ConstructionType.UnknownConstructionType
import fi.liikennevirasto.digiroad2.asset.LinkGeomSource.HistoryLinkInterface
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.vvh.{ChangeInfo, ChangeType, FeatureClass, VVHHistoryRoadLink}
import fi.liikennevirasto.digiroad2.linearasset.RoadLink
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.dao.Discontinuity.Continuous
import fi.liikennevirasto.viite.dao.FloatingReason.NoFloating
import fi.liikennevirasto.viite.dao.{FloatingReason, LinearLocation, RoadAddress, Roadway}
import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination
import org.joda.time.DateTime

object Dummies {

  def dummyRoadway(roadwayNumber: Long, roadNumber: Long, roadPartNumber: Long, startAddrM: Long, endAddrM: Long, startDate: DateTime, endDate: Option[DateTime]) = {
    Roadway(0L, roadwayNumber, roadNumber, roadPartNumber, RoadType.PublicRoad, Track.Combined, Continuous, startAddrM, endAddrM, false, startDate, endDate, "", None, 0L, NoTermination)
  }

  def dummyLinearLocation(id: Long, roadwayNumber: Long, orderNumber: Double, linkId: Long, startMValue: Double, endMValue: Double, vvhTimestamp: Long): LinearLocation =
    dummyLinearLocation(id, roadwayNumber, orderNumber, linkId, startMValue, endMValue, NoFloating, LinkGeomSource.NormalLinkInterface, vvhTimestamp)

  def dummyLinearLocation(id: Long, roadwayNumber: Long, orderNumber: Double, linkId: Long, startMValue: Double, endMValue: Double, floatingReason: FloatingReason, vvhTimestamp: Long): LinearLocation =
    dummyLinearLocation(id, roadwayNumber, orderNumber, linkId, startMValue, endMValue, floatingReason, LinkGeomSource.NormalLinkInterface, vvhTimestamp)

  def dummyLinearLocation(roadwayNumber: Long, orderNumber: Double, linkId: Long, startMValue: Double, endMValue: Double): LinearLocation =
    dummyLinearLocation(roadwayNumber, orderNumber, linkId, startMValue, endMValue, NoFloating, LinkGeomSource.NormalLinkInterface)

  def dummyLinearLocation(roadwayNumber: Long, orderNumber: Double, linkId: Long, startMValue: Double, endMValue: Double, floatingReason: FloatingReason): LinearLocation =
    dummyLinearLocation(roadwayNumber, orderNumber, linkId, startMValue, endMValue, floatingReason, LinkGeomSource.NormalLinkInterface)

  def dummyLinearLocation(roadwayNumber: Long, orderNumber: Double, linkId: Long, startMValue: Double, endMValue: Double, floatingReason: FloatingReason, linkGeomSource: LinkGeomSource): LinearLocation = {
    dummyLinearLocation(roadwayNumber + Math.round(orderNumber), roadwayNumber, orderNumber, linkId, startMValue, endMValue, floatingReason, LinkGeomSource.NormalLinkInterface, vvhTimestamp = 0L)
  }

  def dummyLinearLocation(id: Long, roadwayNumber: Long, orderNumber: Double, linkId: Long, startMValue: Double, endMValue: Double, floatingReason: FloatingReason, linkGeomSource: LinkGeomSource, vvhTimestamp: Long): LinearLocation = {
    LinearLocation(id, orderNumber, linkId, startMValue, endMValue, SideCode.TowardsDigitizing, vvhTimestamp, (None, None), floatingReason,
      Seq(Point(0.0, startMValue), Point(0.0, endMValue)), linkGeomSource, roadwayNumber)
  }

  def dummyRoadAddress(roadwayNumber: Long, roadNumber: Long, roadPartNumber: Long, startAddrM: Long, endAddrM: Long, startDate: Option[DateTime], endDate: Option[DateTime],
                       linkId: Long, startMValue: Double, endMValue: Double, linkGeomSource: LinkGeomSource): RoadAddress =
    dummyRoadAddress(roadwayNumber, roadNumber, roadPartNumber, startAddrM, endAddrM, startDate, endDate, linkId, startMValue, endMValue, NoFloating, linkGeomSource)

  def dummyRoadAddress(roadwayNumber: Long, roadNumber: Long, roadPartNumber: Long, startAddrM: Long, endAddrM: Long, startDate: Option[DateTime], endDate: Option[DateTime],
                       linkId: Long, startMValue: Double, endMValue: Double, floatingReason: FloatingReason, linkGeomSource: LinkGeomSource): RoadAddress = {
    RoadAddress(0L, 0L, roadNumber, roadPartNumber, RoadType.PublicRoad, Track.Combined, Continuous, startAddrM, endAddrM, startDate, endDate, None, linkId, startMValue, endMValue, SideCode.TowardsDigitizing,
      0L, (None, None), floatingReason, Seq(Point(0.0, startMValue), Point(0.0, endMValue)), linkGeomSource, 0L, NoTermination, roadwayNumber, None, None, None)
  }

  def dummyVvhHistoryRoadLink(linkId: Long, yCoordinates: Seq[Double]): VVHHistoryRoadLink = {
    val municipalityCode = 0
    VVHHistoryRoadLink(linkId, municipalityCode, yCoordinates.map(y => Point(0.0, y)), Municipality, TrafficDirection.TowardsDigitizing, FeatureClass.DrivePath, 0L, 0L, Map(), UnknownConstructionType, HistoryLinkInterface, yCoordinates.sum)
  }

  def dummyRoadLink(linkId: Long, yCoordinates: Seq[Double], linkGeomSource: LinkGeomSource): RoadLink = {
    RoadLink(linkId, yCoordinates.map(y => Point(0.0, y)), yCoordinates.sum, Municipality, 0, TrafficDirection.TowardsDigitizing, UnknownLinkType, None, None, Map(), UnknownConstructionType, linkGeomSource)
  }

  def dummyChangeInfo(changeType: ChangeType, oldId: Long, newId: Long, oldStartMeasure: Double, oldEndMeasure: Double, newStartMeasure: Double, newEndMeasure: Double, vvhTimeStamp: Long): ChangeInfo = {
    ChangeInfo(Some(oldId), Some(newId), oldId + newId, changeType, Some(oldStartMeasure), Some(oldEndMeasure), Some(newStartMeasure), Some(newEndMeasure), vvhTimeStamp)
  }

  def dummyNewChangeInfo(changeType: ChangeType, newId: Long, newStartMeasure: Double, newEndMeasure: Double, vvhTimeStamp: Long): ChangeInfo = {
    ChangeInfo(None, Some(newId), newId, changeType, None, None, Some(newStartMeasure), Some(newEndMeasure), vvhTimeStamp)
  }

  def dummyOldChangeInfo(changeType: ChangeType, oldId: Long, oldStartMeasure: Double, oldEndMeasure: Double, vvhTimeStamp: Long): ChangeInfo = {
    ChangeInfo(Some(oldId), None, oldId, changeType, Some(oldStartMeasure), Some(oldEndMeasure), None, None, vvhTimeStamp)
  }
}

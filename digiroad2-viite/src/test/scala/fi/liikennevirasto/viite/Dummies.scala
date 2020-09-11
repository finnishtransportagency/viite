package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset.LifecycleStatus.UnknownLifecycleStatus$
import fi.liikennevirasto.digiroad2.asset.LinkGeomSource.NormalLinkInterface
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.kmtk.{ChangeInfo, ChangeType, KMTKHistoryRoadLink}
import fi.liikennevirasto.digiroad2.client.vvh.FeatureClass
import fi.liikennevirasto.digiroad2.linearasset.{KMTKID, RoadLink}
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.CalibrationPointType.NoCP
import fi.liikennevirasto.viite.dao.Discontinuity.Continuous
import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination
import fi.liikennevirasto.viite.dao._
import org.joda.time.DateTime

object Dummies {

  def dummyRoadway(roadwayNumber: Long, roadNumber: Long, roadPartNumber: Long, startAddrM: Long, endAddrM: Long, startDate: DateTime, endDate: Option[DateTime], roadwayId: Long = 0L): Roadway = {
    Roadway(roadwayId, roadwayNumber, roadNumber, roadPartNumber, RoadType.PublicRoad, Track.Combined, Continuous, startAddrM, endAddrM, reversed = false, startDate, endDate, "user", None, 0L, NoTermination)
  }

  def dummyRoadwayChangeSection(roadNumber: Option[Long], roadPartNumber: Option[Long], track: Option[Long], startAddressM: Option[Long], endAddressM: Option[Long], roadType: Option[RoadType] = Some(RoadType.PublicRoad), discontinuity: Option[Discontinuity], ely: Option[Long]) = {
    RoadwayChangeSection(roadNumber, track, roadPartNumber, roadPartNumber, startAddressM, endAddressM, roadType, discontinuity, ely)
  }

  def dummyLinearLocationWithGeometry(id: Long, roadwayNumber: Long, orderNumber: Double, linkId: Long, startMValue: Double, endMValue: Double, sideCode: SideCode = SideCode.TowardsDigitizing, geometry: Seq[Point] = Seq()): LinearLocation =
    dummyLinearLocation(id, roadwayNumber, orderNumber, linkId, startMValue, endMValue, sideCode, LinkGeomSource.NormalLinkInterface, 0, geometry)

  def dummyLinearLocation(id: Long, roadwayNumber: Long, orderNumber: Double, linkId: Long, startMValue: Double, endMValue: Double, timestamp: Long): LinearLocation =
    dummyLinearLocation(id, roadwayNumber, orderNumber, linkId, startMValue, endMValue, SideCode.TowardsDigitizing, LinkGeomSource.NormalLinkInterface, timestamp)

  def dummyLinearLocation(roadwayNumber: Long, orderNumber: Double, linkId: Long, startMValue: Double, endMValue: Double): LinearLocation =
    dummyLinearLocation(roadwayNumber, orderNumber, linkId, startMValue, endMValue, LinkGeomSource.NormalLinkInterface)

  def dummyLinearLocation(roadwayNumber: Long, orderNumber: Double, linkId: Long, startMValue: Double, endMValue: Double, linkGeomSource: LinkGeomSource): LinearLocation = {
    dummyLinearLocation(roadwayNumber + Math.round(orderNumber), roadwayNumber, orderNumber, linkId, startMValue, endMValue, SideCode.TowardsDigitizing, LinkGeomSource.NormalLinkInterface, timestamp = 0L)
  }

  def dummyLinearLocation(id: Long, roadwayNumber: Long, orderNumber: Double, linkId: Long, startMValue: Double, endMValue: Double, sideCode: SideCode = SideCode.TowardsDigitizing, linkGeomSource: LinkGeomSource, timestamp: Long = 0L, geometry: Seq[Point] = Seq()): LinearLocation = {
    LinearLocation(id, orderNumber, linkId, startMValue, endMValue, sideCode, timestamp, (CalibrationPointReference.None, CalibrationPointReference.None),
      if (geometry.isEmpty) Seq(Point(0.0, startMValue), Point(0.0, endMValue)) else geometry, linkGeomSource, roadwayNumber)
  }

  def dummyRoadAddress(roadwayNumber: Long, roadNumber: Long, roadPartNumber: Long, startAddrM: Long, endAddrM: Long, startDate: Option[DateTime], endDate: Option[DateTime],
                       linkId: Long, startMValue: Double, endMValue: Double, linkGeomSource: LinkGeomSource): RoadAddress =
    dummyRoadAddress(roadwayNumber, roadNumber, roadPartNumber, startAddrM, endAddrM, startDate, endDate, linkId, startMValue, endMValue, linkGeomSource)

  def dummyRoadAddress(roadwayNumber: Long, roadNumber: Long, roadPartNumber: Long, startAddrM: Long, endAddrM: Long, startDate: Option[DateTime], endDate: Option[DateTime],
                       linkId: Long, startMValue: Double, endMValue: Double, linkGeomSource: LinkGeomSource, geometry: Seq[Point] = Seq()): RoadAddress = {
    RoadAddress(0L, 0L, roadNumber, roadPartNumber, RoadType.PublicRoad, Track.Combined, Continuous, startAddrM, endAddrM, startDate, endDate, None, linkId, startMValue, endMValue, SideCode.TowardsDigitizing,
      0L, (None, None), if (geometry.nonEmpty) geometry else Seq(Point(0.0, startMValue), Point(0.0, endMValue)), linkGeomSource, 0L, NoTermination, roadwayNumber, None, None, None)
  }

  def dummyProjectLink(roadNumber: Long, roadPartNumber: Long, trackCode: Track, discontinuityType: Discontinuity, startAddrM: Long, endAddrM: Long, startDate: Option[DateTime], endDate: Option[DateTime] = None, linkId: Long = 0, startMValue: Double = 0,
                       endMValue: Double = 0, sideCode: SideCode = SideCode.Unknown, status: LinkStatus, projectId: Long = 0, roadType: RoadType = RoadType.PublicRoad, geometry: Seq[Point] = Seq(), roadwayNumber: Long = 0L) = {
    ProjectLink(0L, roadNumber, roadPartNumber, trackCode, discontinuityType, startAddrM, endAddrM, startAddrM, endAddrM, startDate, endDate,
      Some("user"), linkId, startMValue, endMValue, sideCode, (NoCP, NoCP), (NoCP, NoCP), geometry, projectId,
      status, roadType, geometryLength = 0, roadwayId = 0, linearLocationId = 0, ely = 8, reversed = false, linkGeometryTimeStamp = 0, roadwayNumber = roadwayNumber)
  }

  def dummyHistoryRoadLink(linkId: Long, yCoordinates: Seq[Double]): KMTKHistoryRoadLink = {
    val municipalityCode = 0
    KMTKHistoryRoadLink(linkId, KMTKID("UUID", 1), municipalityCode, yCoordinates.map(y => Point(0.0, y)), Municipality, TrafficDirection.TowardsDigitizing, FeatureClass.DrivePath, 0L, 0L, Map(), UnknownLifecycleStatus$, NormalLinkInterface, yCoordinates.sum)
  }

  def dummyRoadLink(linkId: Long, yCoordinates: Seq[Double], linkGeomSource: LinkGeomSource): RoadLink = {
    RoadLink(linkId, KMTKID("UUID", 1), yCoordinates.map(y => Point(0.0, y)), yCoordinates.sum, Municipality, 0, TrafficDirection.TowardsDigitizing, UnknownLinkType, None, None, Map(), UnknownLifecycleStatus$, linkGeomSource)
  }

  def dummyChangeInfo(changeType: ChangeType, oldId: Long, newId: Long, oldStartMeasure: Double, oldEndMeasure: Double, newStartMeasure: Double, newEndMeasure: Double, timeStamp: Long): ChangeInfo = {
    ChangeInfo(Some(oldId), Some(newId), oldId + newId, changeType, Some(oldStartMeasure), Some(oldEndMeasure), Some(newStartMeasure), Some(newEndMeasure), timeStamp)
  }

  def dummyNewChangeInfo(changeType: ChangeType, newId: Long, newStartMeasure: Double, newEndMeasure: Double, timeStamp: Long): ChangeInfo = {
    ChangeInfo(None, Some(newId), newId, changeType, None, None, Some(newStartMeasure), Some(newEndMeasure), timeStamp)
  }

  def dummyOldChangeInfo(changeType: ChangeType, oldId: Long, oldStartMeasure: Double, oldEndMeasure: Double, timeStamp: Long): ChangeInfo = {
    ChangeInfo(Some(oldId), None, oldId, changeType, Some(oldStartMeasure), Some(oldEndMeasure), None, None, timeStamp)
  }
}

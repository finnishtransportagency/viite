package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.asset.LifecycleStatus.UnknownLifecycleStatus
import fi.liikennevirasto.digiroad2.asset.LinkGeomSource.HistoryLinkInterface
import fi.liikennevirasto.digiroad2.client.vvh.{ChangeInfo, ChangeType, FeatureClass, HistoryRoadLink}
import fi.liikennevirasto.digiroad2.linearasset.RoadLink
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.CalibrationPointType.NoCP
import fi.liikennevirasto.viite.dao.Discontinuity.Continuous
import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination
import org.joda.time.DateTime

object Dummies {

  def dummyRoadway(roadwayNumber: Long, roadNumber: Long, roadPartNumber: Long, startAddrM: Long, endAddrM: Long, startDate: DateTime, endDate: Option[DateTime], roadwayId: Long = 0L): Roadway = {
    Roadway(roadwayId, roadwayNumber, roadNumber, roadPartNumber, AdministrativeClass.State, Track.Combined, Continuous, startAddrM, endAddrM, reversed = false, startDate, endDate, "user", None, 0L, NoTermination)
  }

  def dummyRoadwayChangeSection(roadNumber: Option[Long], roadPartNumber: Option[Long], track: Option[Long], startAddressM: Option[Long], endAddressM: Option[Long], administrativeClass: Option[AdministrativeClass] = Some(AdministrativeClass.State), discontinuity: Option[Discontinuity], ely: Option[Long]): RoadwayChangeSection = {
    RoadwayChangeSection(roadNumber, track, roadPartNumber, roadPartNumber, startAddressM, endAddressM, administrativeClass, discontinuity, ely)
  }

  def dummyLinearLocationWithGeometry(id: Long, roadwayNumber: Long, orderNumber: Double, linkId: String, startMValue: Double, endMValue: Double, sideCode: SideCode = SideCode.TowardsDigitizing, geometry: Seq[Point] = Seq()): LinearLocation =
    dummyLinearLocation(id, roadwayNumber, orderNumber, linkId, startMValue, endMValue, sideCode, LinkGeomSource.NormalLinkInterface, 0, geometry)

  def dummyLinearLocation(id: Long, roadwayNumber: Long, orderNumber: Double, linkId: String, startMValue: Double, endMValue: Double, vvhTimestamp: Long): LinearLocation =
    dummyLinearLocation(id, roadwayNumber, orderNumber, linkId, startMValue, endMValue, SideCode.TowardsDigitizing, LinkGeomSource.NormalLinkInterface, vvhTimestamp)

  def dummyLinearLocation(roadwayNumber: Long, orderNumber: Double, linkId: String, startMValue: Double, endMValue: Double): LinearLocation =
    dummyLinearLocation(roadwayNumber, orderNumber, linkId, startMValue, endMValue, LinkGeomSource.NormalLinkInterface)

  def dummyLinearLocation(roadwayNumber: Long, orderNumber: Double, linkId: String, startMValue: Double, endMValue: Double, linkGeomSource: LinkGeomSource): LinearLocation = {
    dummyLinearLocation(roadwayNumber + Math.round(orderNumber), roadwayNumber, orderNumber, linkId, startMValue, endMValue, SideCode.TowardsDigitizing, LinkGeomSource.NormalLinkInterface, vvhTimestamp = 0L)
  }

  def dummyLinearLocation(id: Long, roadwayNumber: Long, orderNumber: Double, linkId: String, startMValue: Double, endMValue: Double, sideCode: SideCode = SideCode.TowardsDigitizing, linkGeomSource: LinkGeomSource, vvhTimestamp: Long = 0L, geometry: Seq[Point] = Seq()): LinearLocation = {
    LinearLocation(id, orderNumber, linkId, startMValue, endMValue, sideCode, vvhTimestamp, (CalibrationPointReference.None, CalibrationPointReference.None), if (geometry.isEmpty) Seq(Point(0.0, startMValue), Point(0.0, endMValue)) else geometry, linkGeomSource, roadwayNumber)
  }

  def dummyRoadAddress(roadwayNumber: Long, roadNumber: Long, roadPartNumber: Long, startAddrM: Long, endAddrM: Long, startDate: Option[DateTime], endDate: Option[DateTime], linkId: String, startMValue: Double, endMValue: Double, linkGeomSource: LinkGeomSource): RoadAddress =
    dummyRoadAddress(roadwayNumber, roadNumber, roadPartNumber, startAddrM, endAddrM, startDate, endDate, linkId, startMValue, endMValue, linkGeomSource)

  def dummyRoadAddress(roadwayNumber: Long, roadNumber: Long, roadPartNumber: Long, startAddrM: Long, endAddrM: Long, startDate: Option[DateTime], endDate: Option[DateTime], linkId: String, startMValue: Double, endMValue: Double, linkGeomSource: LinkGeomSource, geometry: Seq[Point] = Seq()): RoadAddress = {
    RoadAddress(0L, 0L, roadNumber, roadPartNumber, AdministrativeClass.State, Track.Combined, Continuous, startAddrM, endAddrM, startDate, endDate, None, linkId, startMValue, endMValue, SideCode.TowardsDigitizing, 0L, (None, None), if (geometry.nonEmpty) geometry else Seq(Point(0.0, startMValue), Point(0.0, endMValue)), linkGeomSource, 0L, NoTermination, roadwayNumber, None, None, None)
  }

  def dummyProjectLink(roadNumber: Long, roadPartNumber: Long, trackCode: Track, discontinuityType: Discontinuity, startAddrM: Long, endAddrM: Long, startDate: Option[DateTime], endDate: Option[DateTime] = None, linkId: String = 0.toString, startMValue: Double = 0, endMValue: Double = 0, sideCode: SideCode = SideCode.Unknown, status: LinkStatus, projectId: Long = 0, administrativeClass: AdministrativeClass = AdministrativeClass.State, geometry: Seq[Point] = Seq(), roadwayNumber: Long = 0L): ProjectLink = {
    ProjectLink(0L, roadNumber, roadPartNumber, trackCode, discontinuityType, startAddrM, endAddrM, startAddrM, endAddrM, startDate, endDate, Some("user"), linkId, startMValue, endMValue, sideCode, (NoCP, NoCP), (NoCP, NoCP), geometry, projectId, status, administrativeClass, geometryLength = 0, roadwayId = 0, linearLocationId = 0, ely = 8, reversed = false, linkGeometryTimeStamp = 0, roadwayNumber = roadwayNumber)
  }

  def dummyProject(status: ProjectState, createdDate: DateTime, startDate: DateTime, dateModified: DateTime,reservedParts: Seq[ProjectReservedPart], formedParts: Seq[ProjectReservedPart], statusInfo: Option[String]): Project = {
    Project(0L ,status, "Dummy project", "Viite unittests", createdDate, "Viite unittests", startDate, dateModified, "This project is a dummy project used in unittests and it should be used with runWithRollback to prevent it from saving to the database during test runs",reservedParts, formedParts, statusInfo, None)
  }

  def dummyVvhHistoryRoadLink(linkId: String, yCoordinates: Seq[Double]): HistoryRoadLink = {
    val municipalityCode = 0
    HistoryRoadLink(linkId, municipalityCode, yCoordinates.map(y => Point(0.0, y)), AdministrativeClass.Municipality, TrafficDirection.TowardsDigitizing, FeatureClass.DrivePath, 0L, 0L, Map(), UnknownLifecycleStatus, HistoryLinkInterface, yCoordinates.sum)
  }

  def dummyRoadLink(linkId: String, yCoordinates: Seq[Double], linkGeomSource: LinkGeomSource): RoadLink = {
    RoadLink(linkId, yCoordinates.map(y => Point(0.0, y)), yCoordinates.sum, AdministrativeClass.Municipality, TrafficDirection.TowardsDigitizing, None, None, UnknownLifecycleStatus, linkGeomSource, 0, "")
  }

  def dummyChangeInfo(changeType: ChangeType, oldId: String, newId: String, oldStartMeasure: Double, oldEndMeasure: Double, newStartMeasure: Double, newEndMeasure: Double, vvhTimeStamp: Long): ChangeInfo = {
    ChangeInfo(Some(oldId), Some(newId), (oldId + newId).toLong, changeType, Some(oldStartMeasure), Some(oldEndMeasure), Some(newStartMeasure), Some(newEndMeasure), vvhTimeStamp)
  }

  def dummyNewChangeInfo(changeType: ChangeType, newId: String, newStartMeasure: Double, newEndMeasure: Double, vvhTimeStamp: Long): ChangeInfo = {
    ChangeInfo(None, Some(newId), newId.toLong, changeType, None, None, Some(newStartMeasure), Some(newEndMeasure), vvhTimeStamp)
  }

  def dummyOldChangeInfo(changeType: ChangeType, oldId: String, oldStartMeasure: Double, oldEndMeasure: Double, vvhTimeStamp: Long): ChangeInfo = {
    ChangeInfo(Some(oldId), None, oldId.toLong, changeType, Some(oldStartMeasure), Some(oldEndMeasure), None, None, vvhTimeStamp)
  }
}

package fi.liikennevirasto.viite.model

import fi.liikennevirasto.digiroad2.{Point, Vector3d}
import fi.liikennevirasto.digiroad2.asset.{AdministrativeClass, _}
import fi.liikennevirasto.digiroad2.linearasset.PolyLine
import fi.liikennevirasto.viite.dao.CalibrationPoint

trait RoadAddressLinkLike extends PolyLine {
  def id: Long
  def linearLocationId: Long
  def linkId: String
  def length: Double
  def administrativeClassMML: AdministrativeClass
  def lifecycleStatus: LifecycleStatus
  def roadLinkSource: LinkGeomSource
  def administrativeClass: AdministrativeClass

  def roadName: Option[String]
  def municipalityCode: BigInt
  def municipalityName: String
  def modifiedAt: Option[String]
  def modifiedBy: Option[String]
  def roadNumber: Long
  def roadPartNumber: Long
  def trackCode: Long
  def elyCode: Long
  def discontinuity: Long
  def startAddressM: Long
  def endAddressM: Long
  def startMValue: Double
  def endMValue: Double
  def sideCode: SideCode
  def startCalibrationPoint: Option[CalibrationPoint]
  def endCalibrationPoint: Option[CalibrationPoint]
  def roadwayNumber: Long
  def sourceId: String
}

case class RoadAddressLink(id: Long, linearLocationId: Long, linkId: String, geometry: Seq[Point], length: Double, administrativeClassMML: AdministrativeClass, lifecycleStatus: LifecycleStatus, roadLinkSource: LinkGeomSource, administrativeClass: AdministrativeClass, roadName: Option[String], municipalityCode: BigInt, municipalityName: String, modifiedAt: Option[String], modifiedBy: Option[String], roadNumber: Long, roadPartNumber: Long, trackCode: Long, elyCode: Long, discontinuity: Long, startAddressM: Long, endAddressM: Long, startDate: String, endDate: String, startMValue: Double, endMValue: Double, sideCode: SideCode, startCalibrationPoint: Option[CalibrationPoint], endCalibrationPoint: Option[CalibrationPoint],  roadwayNumber: Long = 0, newGeometry: Option[Seq[Point]] = None, sourceId: String) extends RoadAddressLinkLike {

  lazy val startingPoint: Point = if(sideCode == SideCode.TowardsDigitizing)
      geometry.head
    else
      geometry.last


  lazy val endPoint: Point = if(sideCode == SideCode.TowardsDigitizing)
      geometry.last
    else
      geometry.head


  def getEndPoints: (Point, Point) = {
    if (sideCode == SideCode.Unknown) {
      val direction = if (geometry.head.y == geometry.last.y) Vector3d(1.0, 0.0, 0.0) else Vector3d(0.0, 1.0, 0.0)
      Seq((geometry.head, geometry.last), (geometry.last, geometry.head)).minBy(ps => direction.dot(ps._1.toVector - ps._2.toVector))
    } else {
      (startingPoint, endPoint)
    }
  }
}

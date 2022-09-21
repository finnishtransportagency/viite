package fi.liikennevirasto.viite.model

import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset.{AdministrativeClass, _}
import fi.liikennevirasto.viite.dao.{CalibrationPoint, LinkStatus}
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.CalibrationPointType
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.CalibrationPointType.NoCP

trait ProjectAddressLinkLike extends RoadAddressLinkLike {
  def id: Long
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
  def attributes: Map[String, Any]
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
  def anomaly: Anomaly
  def status: LinkStatus
  def roadwayId: Long
  def connectedLinkId: Option[String]
  def partitioningName: String
  def isSplit: Boolean
  def originalGeometry: Option[Seq[Point]]
  def roadwayNumber: Long
}

case class ProjectAddressLink(id: Long, linkId: String, geometry: Seq[Point], length: Double, administrativeClassMML: AdministrativeClass, lifecycleStatus: LifecycleStatus, roadLinkSource: LinkGeomSource, administrativeClass: AdministrativeClass, VVHRoadName: Option[String], roadName: Option[String], municipalityCode: BigInt, municipalityName: String, modifiedAt: Option[String], modifiedBy: Option[String], attributes: Map[String, Any] = Map(), roadNumber: Long, roadPartNumber: Long, trackCode: Long, elyCode: Long, discontinuity: Long, startAddressM: Long, endAddressM: Long, startMValue: Double, endMValue: Double, sideCode: SideCode, startCalibrationPoint: Option[CalibrationPoint], endCalibrationPoint: Option[CalibrationPoint], anomaly: Anomaly = Anomaly.None, status: LinkStatus, roadwayId: Long, linearLocationId: Long, reversed: Boolean = false, connectedLinkId: Option[String] = None, originalGeometry: Option[Seq[Point]] = None, roadwayNumber: Long = 0) extends ProjectAddressLinkLike {
  override def partitioningName: String = {
    if (roadNumber > 0)
      s"$roadNumber/$roadPartNumber/$trackCode"
    else
      VVHRoadName.getOrElse("")
  }

  override def isSplit: Boolean = {
    connectedLinkId.nonEmpty || connectedLinkId.contains(0L.toString)
  }

  val vvhTimeStamp: Long = attributes.getOrElse("LAST_EDITED_DATE", attributes.getOrElse("CREATED_DATE", BigInt(0))).asInstanceOf[BigInt].longValue()

  def startCalibrationPointType: CalibrationPointType = {
    if (startCalibrationPoint.isDefined) startCalibrationPoint.get.typeCode
    else NoCP
  }

  def endCalibrationPointType: CalibrationPointType = {
    if (endCalibrationPoint.isDefined) endCalibrationPoint.get.typeCode
    else NoCP
  }

}

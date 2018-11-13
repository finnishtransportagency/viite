package fi.liikennevirasto.viite.model

import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.viite.RoadType
import fi.liikennevirasto.viite.dao.{CalibrationPoint, LinkStatus}

trait ProjectAddressLinkLike extends RoadAddressLinkLike {
  def id: Long
  def linkId: Long
  def length: Double
  def administrativeClass: AdministrativeClass
  def linkType: LinkType
  def constructionType: ConstructionType
  def roadLinkSource: LinkGeomSource
  def roadType: RoadType

  def roadName: Option[String]
  def municipalityCode: BigInt
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
  def connectedLinkId: Option[Long]
  def partitioningName: String
  def isSplit: Boolean
  def originalGeometry: Option[Seq[Point]]
}

case class ProjectAddressLink(id: Long, linkId: Long, geometry: Seq[Point],
                              length: Double, administrativeClass: AdministrativeClass,
                              linkType: LinkType, constructionType: ConstructionType,
                              roadLinkSource: LinkGeomSource, roadType: RoadType, VVHRoadName: Option[String], roadName: Option[String], municipalityCode: BigInt, modifiedAt: Option[String], modifiedBy: Option[String],
                              attributes: Map[String, Any] = Map(), roadNumber: Long, roadPartNumber: Long, trackCode: Long, elyCode: Long, discontinuity: Long,
                              startAddressM: Long, endAddressM: Long, startMValue: Double, endMValue: Double, sideCode: SideCode,
                              startCalibrationPoint: Option[CalibrationPoint], endCalibrationPoint: Option[CalibrationPoint],
                              anomaly: Anomaly = Anomaly.None, status: LinkStatus, roadwayId: Long, linearLocationId: Long, reversed: Boolean = false,
                              connectedLinkId: Option[Long] = None, originalGeometry: Option[Seq[Point]] = None) extends ProjectAddressLinkLike {
  override def partitioningName: String = {
    if (roadNumber > 0)
      s"$roadNumber/$roadPartNumber/$trackCode"
    else
      VVHRoadName.getOrElse("")
  }

  override def isSplit: Boolean = {
    connectedLinkId.nonEmpty || connectedLinkId.contains(0L)
  }

  val vvhTimeStamp: Long = attributes.getOrElse("LAST_EDITED_DATE", attributes.getOrElse("CREATED_DATE", BigInt(0))).asInstanceOf[BigInt].longValue()

}

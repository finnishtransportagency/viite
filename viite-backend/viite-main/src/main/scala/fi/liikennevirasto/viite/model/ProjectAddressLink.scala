package fi.liikennevirasto.viite.model

import fi.liikennevirasto.viite.dao.ProjectCalibrationPoint
import fi.vaylavirasto.viite.geometry.Point
import fi.vaylavirasto.viite.model.{AddrMRange, AdministrativeClass, CalibrationPointType, LifecycleStatus, LinkGeomSource, RoadAddressChangeType, RoadPart, SideCode}
import org.joda.time.DateTime

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
  def roadPart: RoadPart
  def trackCode: Long
  def elyCode: Long
  def discontinuity: Long
  def addrMRange: AddrMRange
  def startMValue: Double
  def endMValue: Double
  def sideCode: SideCode
  def startCalibrationPoint: Option[ProjectCalibrationPoint]
  def endCalibrationPoint: Option[ProjectCalibrationPoint]
  def status: RoadAddressChangeType
  def roadwayId: Long
  def connectedLinkId: Option[String]
  def partitioningName: String
  def isSplit: Boolean
  def originalGeometry: Option[Seq[Point]]
  def roadwayNumber: Long
  def roadAddressRoadPart: Option[RoadPart]
}

case class ProjectAddressLink(id                    : Long,
                              linkId                : String,
                              geometry              : Seq[Point],
                              length                : Double,
                              administrativeClassMML: AdministrativeClass,
                              lifecycleStatus       : LifecycleStatus,
                              roadLinkSource        : LinkGeomSource,
                              administrativeClass   : AdministrativeClass,
                              roadName              : Option[String],
                              municipalityCode      : BigInt,
                              municipalityName      : String,
                              modifiedAt            : Option[String],
                              modifiedBy            : Option[String],
                              roadPart              : RoadPart,
                              trackCode             : Long,
                              elyCode               : Long,
                              discontinuity         : Long,
                              addrMRange            : AddrMRange,
                              startMValue           : Double,
                              endMValue             : Double,
                              sideCode              : SideCode,
                              startCalibrationPoint : Option[ProjectCalibrationPoint],
                              endCalibrationPoint   : Option[ProjectCalibrationPoint],
                              status                : RoadAddressChangeType,
                              roadwayId             : Long,
                              linearLocationId      : Long,
                              reversed              : Boolean = false,
                              connectedLinkId       : Option[String] = None,
                              originalGeometry      : Option[Seq[Point]] = None,
                              roadwayNumber         : Long = 0,
                              sourceId              : String,
                              roadAddressRoadPart   : Option[RoadPart] = None
                             ) extends ProjectAddressLinkLike {
  override def partitioningName: String = {
    if (roadPart.roadNumber > 0) // TODO comparing to 0 obsolete, RoadPart does not allow values <1
      s"$roadPart/$trackCode"
    else
      ""
  }

  override def isSplit: Boolean = {
    connectedLinkId.nonEmpty || connectedLinkId.contains(0L.toString)
  }

  def roadLinkTimeStamp: Long = new DateTime(modifiedAt.getOrElse(throw new RuntimeException("Roadlink timestamp not available because ModifiedAt was not defined.") )).getMillis

  def startCalibrationPointType: CalibrationPointType = {
    if (startCalibrationPoint.isDefined) startCalibrationPoint.get.typeCode
    else CalibrationPointType.NoCP
  }

  def endCalibrationPointType: CalibrationPointType = {
    if (endCalibrationPoint.isDefined) endCalibrationPoint.get.typeCode
    else CalibrationPointType.NoCP
  }

}

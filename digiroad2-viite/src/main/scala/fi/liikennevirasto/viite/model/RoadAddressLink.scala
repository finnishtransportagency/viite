package fi.liikennevirasto.viite.model

import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.linearasset.PolyLine
import fi.liikennevirasto.digiroad2.{Point, Vector3d}
import fi.liikennevirasto.viite.RoadType
import fi.liikennevirasto.viite.dao.CalibrationPoint

trait RoadAddressLinkLike extends PolyLine {
  def id: Long
  def linearLocationId: Long
  def linkId: Long
  def length: Double
  def administrativeClass: AdministrativeClass
  def linkType: LinkType
  def constructionType: ConstructionType
  def roadLinkSource: LinkGeomSource
  def roadType: RoadType

  def VVHRoadName: Option[String]

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
  def roadwayNumber: Long
}

case class RoadAddressLink(id: Long, linearLocationId: Long, linkId: Long, geometry: Seq[Point],
                           length: Double, administrativeClass: AdministrativeClass,
                           linkType: LinkType, constructionType: ConstructionType, roadLinkSource: LinkGeomSource, roadType: RoadType, VVHRoadName: Option[String], roadName: Option[String], municipalityCode: BigInt, municipalityName: String, modifiedAt: Option[String], modifiedBy: Option[String],
                           attributes: Map[String, Any] = Map(), roadNumber: Long, roadPartNumber: Long, trackCode: Long, elyCode: Long, discontinuity: Long,
                           startAddressM: Long, endAddressM: Long, startDate: String, endDate: String, startMValue: Double, endMValue: Double, sideCode: SideCode,
                           startCalibrationPoint: Option[CalibrationPoint], endCalibrationPoint: Option[CalibrationPoint],
                           anomaly: Anomaly = Anomaly.None, roadwayNumber: Long = 0, newGeometry: Option[Seq[Point]] = None) extends RoadAddressLinkLike {

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

sealed trait Anomaly {
  def value: Int
}

object Anomaly {
  val values = Set(None, NoAddressGiven, GeometryChanged, Illogical)

  def apply(intValue: Int): Anomaly = {
    values.find(_.value == intValue).getOrElse(None)
  }

  case object None extends Anomaly { def value = 0 }
  case object NoAddressGiven extends Anomaly { def value = 1 }
  case object GeometryChanged extends Anomaly { def value = 2 }
  case object Illogical extends Anomaly { def value = 3 }

}
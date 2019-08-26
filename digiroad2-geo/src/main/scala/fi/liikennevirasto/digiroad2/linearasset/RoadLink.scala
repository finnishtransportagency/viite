package fi.liikennevirasto.digiroad2.linearasset

import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset._

/**
 * KMTK ID consists of uuid and version.
 * Complementary VVH link ids are also saved in uuid and they don't have a version (version = 0).
 */
case class KMTKID(uuid: String, version: Long = 0) {
  def isKMTK: Boolean = uuid.length >= 32
  def isVVH: Boolean = uuid.length < 32
}

trait RoadLinkLike extends PolyLine {
  def linkId: Long

  def kmtkId: KMTKID

  def municipalityCode: Int

  def length: Double

  def administrativeClass: AdministrativeClass

  def trafficDirection: TrafficDirection

  def roadNumber: Option[String]

  def linkSource: LinkGeomSource

  def attributes: Map[String, Any]

  def constructionType: ConstructionType

  def timeStamp: Long
}

case class RoadLink(linkId: Long, kmtkId: KMTKID, geometry: Seq[Point],
                    length: Double, administrativeClass: AdministrativeClass,
                    functionalClass: Int, trafficDirection: TrafficDirection,
                    linkType: LinkType, modifiedAt: Option[String], modifiedBy: Option[String],
                    attributes: Map[String, Any] = Map(), constructionType: ConstructionType = ConstructionType.InUse,
                    linkSource: LinkGeomSource = LinkGeomSource.NormalLinkInterface) extends RoadLinkLike {

  def municipalityCode: Int = attributes("MUNICIPALITYCODE").asInstanceOf[BigInt].intValue

  def roadNumber: Option[String] = attributes.get("ROADNUMBER").map(_.toString)

  val timeStamp: Long = attributes.getOrElse("LAST_EDITED_DATE", attributes.getOrElse("CREATED_DATE", BigInt(0))).asInstanceOf[BigInt].longValue()

}

package fi.liikennevirasto.digiroad2.linearasset

import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset._
import org.joda.time.DateTime

import scala.util.Try

trait RoadLinkLike extends PolyLine{
  def linkId: String
  def municipalityCode: Int
  def length: Double
  def administrativeClass: AdministrativeClass
  def trafficDirection: TrafficDirection
  def roadNumber: Option[String]
  def linkSource: LinkGeomSource
  def attributes: Map[String, Any]
  def constructionType: ConstructionType
  def vvhTimeStamp: Long
}

case class RoadLink(linkId: String, geometry: Seq[Point],
                    length: Double, administrativeClass: AdministrativeClass,
                    functionalClass: Int, trafficDirection: TrafficDirection,
                    linkType: LinkType, modifiedAt: Option[String], modifiedBy: Option[String],
                    attributes: Map[String, Any] = Map(), constructionType: ConstructionType = ConstructionType.InUse,
                    linkSource: LinkGeomSource = LinkGeomSource.NormalLinkInterface) extends RoadLinkLike {

  def municipalityCode: Int = attributes.getOrElse("municipalitycode", attributes.get("MUNICIPALITYCODE")).asInstanceOf[Option[BigInt]].map(_.intValue).get
  def roadNumber: Option[String] = attributes.getOrElse("roadnumber", attributes.get("ROADNUMBER")).asInstanceOf[Option[String]] //attributes.getOrElse[Option[String]]("roadnumber", attributes.get("ROADNUMBER").map(_.toString))
  /*  vvhTimeStamp could be as follows. Long format for old vvh Long-type linkId compatibility.
   new DateTime(attributes.getOrElse("versionstarttime", attributes.getOrElse("starttime", BigInt(0))).asInstanceOf[String]).getMillis */
  val vvhTimeStamp: Long = Try(new DateTime(attributes.getOrElse("versionstarttime", attributes.get("starttime")).asInstanceOf[String]).getMillis)
                              .getOrElse(attributes.getOrElse("LAST_EDITED_DATE", attributes.getOrElse("CREATED_DATE", BigInt(0))).asInstanceOf[BigInt].toLong)
}

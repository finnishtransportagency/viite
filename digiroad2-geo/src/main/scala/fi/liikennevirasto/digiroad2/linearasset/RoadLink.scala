package fi.liikennevirasto.digiroad2.linearasset

import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset._
import org.joda.time.DateTime

import scala.util.Try

trait RoadLinkLike extends PolyLine {
  def linkId:              String
  def municipalityCode:    Int
  def length:              Double
  def administrativeClass: AdministrativeClass
  def trafficDirection:    TrafficDirection
  def roadNumber:          Option[String]
  def linkSource:          LinkGeomSource
  def attributes:          Map[String, Any]
  def lifecycleStatus:     LifecycleStatus
  def vvhTimeStamp:        Long
  def modifiedAt:          Option[String]
}

case class RoadLink(linkId: String, geometry: Seq[Point], length: Double, administrativeClass: AdministrativeClass, trafficDirection: TrafficDirection, modifiedAt: Option[String] = None, modifiedBy: Option[String], attributes: Map[String, Any] = Map(), lifecycleStatus: LifecycleStatus = LifecycleStatus.InUse, linkSource: LinkGeomSource = LinkGeomSource.NormalLinkInterface, municipalityCode: Int)
    extends RoadLinkLike {

  /* vvhTimeStamp Long format for old vvh Long-type linkId compatibility. */
  /* TODO: Simplify this
    * after vvh not used anymore. */ val vvhTimeStamp: Long = Try(new DateTime(attributes.getOrElse("versionstarttime", attributes.get("starttime")).asInstanceOf[String]).getMillis).getOrElse(attributes.getOrElse("LAST_EDITED_DATE", attributes.getOrElse("CREATED_DATE", BigInt(0))).asInstanceOf[BigInt].toLong)

//  def municipalityCode: Int = attributes.getOrElse("municipalitycode", attributes.get("MUNICIPALITYCODE")) match {
//    /* TODO:
//          * Simplify this after vvh not used anymore. */ case m: Int => m
//    case m: String => m.asInstanceOf[String].toInt
//    case m: BigInt => m.asInstanceOf[BigInt].intValue()
//    case m: Some[Any] => m.get.toString.toInt
//  }

  def roadNumber: Option[String] = attributes.getOrElse("roadnumber", attributes.get("ROADNUMBER")).asInstanceOf[Option[String]]
}

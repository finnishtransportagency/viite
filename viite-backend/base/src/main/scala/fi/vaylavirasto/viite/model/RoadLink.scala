package fi.vaylavirasto.viite.model

import fi.vaylavirasto.viite.geometry.{Point, PolyLine}
import org.joda.time.DateTime

trait RoadLinkLike extends PolyLine {
  def linkId:              String
  def municipalityCode:    Int
  def length:              Double
  def administrativeClass: AdministrativeClass
  def trafficDirection:    TrafficDirection
  def linkSource:          LinkGeomSource
  def lifecycleStatus:     LifecycleStatus
  def roadLinkTimeStamp:   Long
  def modifiedAt:          Option[String]
  def sourceId:            String
}

case class RoadLink(
                     linkId             : String,
                     geometry           : Seq[Point],
                     length             : Double,
                     administrativeClass: AdministrativeClass,
                     trafficDirection   : TrafficDirection,
                     modifiedAt         : Option[String] = None,
                     modifiedBy         : Option[String],
                     lifecycleStatus    : LifecycleStatus = LifecycleStatus.InUse,
                     linkSource         : LinkGeomSource = LinkGeomSource.NormalLinkInterface,
                     municipalityCode   : Int,
                     sourceId           : String
                   ) extends RoadLinkLike {

  def roadLinkTimeStamp: Long = new DateTime(modifiedAt.getOrElse(throw new RuntimeException("Roadlink timestamp not available because ModifiedAt was not defined."))).getMillis
}

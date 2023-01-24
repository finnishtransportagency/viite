package fi.liikennevirasto.digiroad2.client.kgv

import fi.liikennevirasto.digiroad2.asset.{AdministrativeClass, LifecycleStatus, LinkGeomSource, TrafficDirection}
import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.linearasset.RoadLink
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

import scala.util.Try

sealed trait FeatureClass
object FeatureClass {
  case object TractorRoad extends FeatureClass
  case object ShoulderRoad extends FeatureClass
  case object DrivePath extends FeatureClass
  case object CycleOrPedestrianPath extends FeatureClass
  case object WinterRoads extends FeatureClass
  case object SpecialTransportWithoutGate extends FeatureClass
  case object SpecialTransportWithGate extends FeatureClass
  case object CarRoad_Ia extends FeatureClass
  case object CarRoad_Ib extends FeatureClass
  case object CarRoad_IIa extends FeatureClass
  case object CarRoad_IIb extends FeatureClass
  case object CarRoad_IIIa extends FeatureClass
  case object CarRoad_IIIb extends FeatureClass
  case object AllOthers extends FeatureClass
}

object Extractor {
  lazy val logger = LoggerFactory.getLogger(getClass)
  val featureClassCodeToFeatureClass: Map[Int, FeatureClass] = Map(
    12316 -> FeatureClass.TractorRoad,
    12317 -> FeatureClass.TractorRoad,
    12318 -> FeatureClass.ShoulderRoad,
    12141 -> FeatureClass.DrivePath,
    12314 -> FeatureClass.CycleOrPedestrianPath,
    12312 -> FeatureClass.WinterRoads,
    12153 -> FeatureClass.SpecialTransportWithoutGate,
    12154 -> FeatureClass.SpecialTransportWithGate,
    12111 -> FeatureClass.CarRoad_Ia,
    12112 -> FeatureClass.CarRoad_Ib,
    12121 -> FeatureClass.CarRoad_IIa,
    12122 -> FeatureClass.CarRoad_IIb,
    12131 -> FeatureClass.CarRoad_IIIa,
    12132 -> FeatureClass.CarRoad_IIIb
  )

  private val trafficDirectionToTrafficDirection: Map[Int, TrafficDirection] = Map(
    0 -> TrafficDirection.BothDirections,
    1 -> TrafficDirection.TowardsDigitizing,
    2 -> TrafficDirection.AgainstDigitizing)

  private  def extractAdministrativeClass(attributes: Map[String, Any]): AdministrativeClass = {
    if (attributes("adminclass").asInstanceOf[String] != null)
      Option(attributes("adminclass").asInstanceOf[String].toInt)
        .map(AdministrativeClass.apply)
        .getOrElse(AdministrativeClass.Unknown)
    else AdministrativeClass.Unknown
  }

  def extractLifecycleStatus(attributes : Map[String, Any]): LifecycleStatus = {
    if (attributes("lifecyclestatus").asInstanceOf[String] != null)
      Option(attributes("lifecyclestatus").asInstanceOf[String].toInt)
        .map(LifecycleStatus.apply)
        .getOrElse(LifecycleStatus.InUse)
    else LifecycleStatus.InUse
  }

  private  def extractTrafficDirection(attributes: Map[String, Any]): TrafficDirection = {
    if (attributes("directiontype").asInstanceOf[String] != null)
      Option(attributes("directiontype").asInstanceOf[String].toInt)
        .map(trafficDirectionToTrafficDirection.getOrElse(_, TrafficDirection.UnknownDirection))
        .getOrElse(TrafficDirection.UnknownDirection)
    else TrafficDirection.UnknownDirection
  }

  /**
   * Extract double value from data. Used for change info start and end measures.
   */
  private def anyToDouble(value: Any): Option[Double] = {
    value match {
      case null => None
      case _ => {
        val doubleValue = Try(value.toString.toDouble).getOrElse(throw new NumberFormatException(s"Failed to convert value: ${value.toString}") )
        Some(doubleValue)
      }
    }
  }

  def toBigInt(value: Int): BigInt = {
    Try(BigInt(value)).getOrElse(throw new NumberFormatException(s"Failed to convert value: ${value.toString}"))
  }

  private def extractModifiedAt(attributes: Map[String, Any]): Option[DateTime] = {
    val validFromDate = Option(new DateTime(attributes("sourcemodificationtime").asInstanceOf[String]).getMillis)
    var lastEditedDate : Option[Long] = Option(0)
    if(attributes.contains("versionstarttime")){
      lastEditedDate = Option(new DateTime(attributes("versionstarttime").asInstanceOf[String]).getMillis)
    }

    lastEditedDate.orElse(validFromDate).map(modifiedTime => new DateTime(modifiedTime))
  }

  def extractFeature(feature: Feature, linkGeomSource: LinkGeomSource): RoadLink = {
    val attributes = feature.properties
    val linkGeometry: Seq[Point] = feature.geometry.coordinates.map(point => {
      Point(anyToDouble(point(0)).get, anyToDouble(point(1)).get, anyToDouble(point(2)).get)
    })
    val sourceid = attributes("sourceid").asInstanceOf[String]
    val linkId = attributes("id").asInstanceOf[String]
    val municipalityCode = Try(attributes("municipalitycode").asInstanceOf[String].toInt).getOrElse(throw new NoSuchElementException(s"Missing mandatory municipalityCode. Check data for linkId: $linkId from $linkGeomSource."))
    val geometryLength: Double = anyToDouble(attributes("horizontallength")).getOrElse(0.0)
    val modifiedBy = "KGV"

    RoadLink(
              linkId,
              linkGeometry,
              geometryLength,
              extractAdministrativeClass(attributes),
              extractTrafficDirection(attributes),
              extractModifiedAt(attributes).map(_.toString),
              Some(modifiedBy),
              extractLifecycleStatus(attributes),
              linkGeomSource,
              municipalityCode,
              sourceid)
  }

  def extracRoadNumberAndPartFeature(feature: Feature): (Long, Long, Int)  = {
    val attributes = feature.properties
    (Try(attributes("roadnumber").asInstanceOf[String].toLong).getOrElse(-1),
     Try(attributes("roadpartnumber").asInstanceOf[String].toLong).getOrElse(-1),
     Try(attributes("municipalitycode").asInstanceOf[String].toInt).getOrElse(-1)
    )
  }
}

package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.{DummyEventBus, DummySerializer, GeometryUtils}
import fi.liikennevirasto.digiroad2.asset.{AdministrativeClass, LinkGeomSource, _}
import fi.liikennevirasto.digiroad2.asset.LinkGeomSource.{apply => _, Unknown => _}
import fi.liikennevirasto.digiroad2.client.vvh.KgvRoadLink
import fi.liikennevirasto.digiroad2.linearasset.{RoadLink, RoadLinkLike}
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.{Track, ViiteProperties}
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.model.{Anomaly, RoadAddressLink}
import fi.liikennevirasto.viite.process.RoadwayAddressMapper

class RoadAddressLinkBuilder(roadwayDAO: RoadwayDAO, linearLocationDAO: LinearLocationDAO, projectLinkDAO: ProjectLinkDAO) extends AddressLinkBuilder {

  val vvhClient = new KgvRoadLink
  val eventBus = new DummyEventBus
  val linkService = new RoadLinkService(vvhClient, eventBus, new DummySerializer, ViiteProperties.vvhRoadlinkFrozen)
  val roadAddressService = new RoadAddressService(linkService, roadwayDAO, linearLocationDAO, new RoadNetworkDAO,new RoadwayPointDAO, new NodePointDAO, new JunctionPointDAO, new RoadwayAddressMapper(roadwayDAO, linearLocationDAO), eventBus, ViiteProperties.vvhRoadlinkFrozen) {
    override def withDynSession[T](f: => T): T = f
    override def withDynTransaction[T](f: => T): T = f
  }

  val roadwayAddressMapper = new RoadwayAddressMapper(roadwayDAO, linearLocationDAO)

  def build(roadLink: RoadLinkLike, roadAddress: RoadAddress): RoadAddressLink = {
    val geom = GeometryUtils.truncateGeometry3D(roadLink.geometry, roadAddress.startMValue, roadAddress.endMValue)
    val length = GeometryUtils.geometryLength(geom)
    val VVHRoadName = getVVHRoadName(roadLink.attributes)
    val roadName = roadAddress.roadName
    val municipalityCode = roadLink.attributes.getOrElse(MunicipalityCode, "0").asInstanceOf[String].toInt
    val municipalityName = municipalityNamesMapping.getOrElse(municipalityCode, "")
    val administrativeClass = roadAddress.administrativeClass match {
      case AdministrativeClass.Unknown => roadLink.administrativeClass
      case _ => roadAddress.administrativeClass
    }
    RoadAddressLink(roadAddress.id, roadAddress.linearLocationId, roadLink.linkId, geom, length, roadLink.administrativeClass, roadLink.lifecycleStatus, roadLink.linkSource, administrativeClass, VVHRoadName, roadName, municipalityCode, municipalityName, roadLink.modifiedAt, Some("vvh_modified"), roadLink.attributes, roadAddress.roadNumber, roadAddress.roadPartNumber, roadAddress.track.value, roadAddress.ely, roadAddress.discontinuity.value, roadAddress.startAddrMValue, roadAddress.endAddrMValue, roadAddress.startDate.map(formatter.print).getOrElse(""), roadAddress.endDate.map(formatter.print).getOrElse(""), roadAddress.startMValue, roadAddress.endMValue, roadAddress.sideCode, roadAddress.startCalibrationPoint, roadAddress.calibrationPoints._2, Anomaly.None, roadAddress.roadwayNumber)
  }

  def build(roadAddress: RoadAddress): RoadAddressLink = {
    val geom = roadAddress.geometry
    val length = GeometryUtils.geometryLength(geom)
    val municipalityCode = 0
    val administrativeClass = roadAddress.administrativeClass
    RoadAddressLink(roadAddress.id, roadAddress.linearLocationId, roadAddress.linkId, geom, length, AdministrativeClass(1), LifecycleStatus.apply(0), LinkGeomSource.apply(1), administrativeClass, Some(""), roadAddress.roadName, municipalityCode, "", Some(""), Some("vvh_modified"), Map(), roadAddress.roadNumber, roadAddress.roadPartNumber, roadAddress.track.value, 0, roadAddress.discontinuity.value, roadAddress.startAddrMValue, roadAddress.endAddrMValue, roadAddress.startDate.map(formatter.print).getOrElse(""), roadAddress.endDate.map(formatter.print).getOrElse(""), roadAddress.startMValue, roadAddress.endMValue, roadAddress.sideCode, roadAddress.startCalibrationPoint, roadAddress.calibrationPoints._2, Anomaly.None, roadAddress.roadwayNumber)
  }


  def build(roadLink: RoadLinkLike, unaddressedRoadLink: UnaddressedRoadLink): RoadAddressLink = {
    roadLink match {
      case rl: RoadLink => buildRoadLink(rl, unaddressedRoadLink)
      case rl: RoadLinkLike => throw new NotImplementedError("buildRoadLinkLike used?")
//      case rl: RoadLinkLike => buildRoadLinkLike(rl, unaddressedRoadLink)
    }
  }

  private def buildRoadLink(roadLink: RoadLink, unaddressedRoadLink: UnaddressedRoadLink): RoadAddressLink = {
    val geom = GeometryUtils.truncateGeometry3D(roadLink.geometry, unaddressedRoadLink.startMValue.getOrElse(0.0), unaddressedRoadLink.endMValue.getOrElse(roadLink.length))
    val length = GeometryUtils.geometryLength(geom)
    val roadLinkRoadNumber = roadLink.attributes.get(RoadNumber).map(toIntNumber).getOrElse(0)
    val roadLinkRoadPartNumber = roadLink.attributes.get(RoadPartNumber).map(toIntNumber).getOrElse(0)
    val VVHRoadName = getVVHRoadName(roadLink.attributes)
    val municipalityCode = roadLink.attributes.getOrElse(MunicipalityCode, 0) match { // TODO: Simplify this after vvh not used anymore.
      case m:String => m.asInstanceOf[String].toInt
      case m:Int => m.asInstanceOf[Int].intValue()
    }
    val municipalityName = municipalityNamesMapping.getOrElse(municipalityCode, "")
    val administrativeClass = unaddressedRoadLink.administrativeClass match {
      case AdministrativeClass.Unknown => roadLink.administrativeClass
      case _ => unaddressedRoadLink.administrativeClass
    }
    RoadAddressLink(0, 0, roadLink.linkId, geom, length, roadLink.administrativeClass, roadLink.lifecycleStatus, roadLink.linkSource, administrativeClass, VVHRoadName, Some(""), municipalityCode, municipalityName, roadLink.modifiedAt, Some("kgv_modified"), roadLink.attributes, unaddressedRoadLink.roadNumber.getOrElse(roadLinkRoadNumber), unaddressedRoadLink.roadPartNumber.getOrElse(roadLinkRoadPartNumber), Track.Unknown.value, municipalityRoadMaintainerMapping.getOrElse(roadLink.municipalityCode, -1), Discontinuity.Continuous.value, 0, 0, "", "", 0.0, length, SideCode.Unknown, None, None, unaddressedRoadLink.anomaly, newGeometry = Some(roadLink.geometry))
  }

//  private def buildRoadLinkLike(roadLink: RoadLinkLike, unaddressedRoadLink: UnaddressedRoadLink): RoadAddressLink = {
//    val geom = GeometryUtils.truncateGeometry3D(roadLink.geometry, unaddressedRoadLink.startMValue.getOrElse(0.0), unaddressedRoadLink.endMValue.getOrElse(roadLink.length))
//    val length = GeometryUtils.geometryLength(geom)
//    val roadLinkRoadNumber = roadLink.attributes.get(RoadNumber).map(toIntNumber).getOrElse(0)
//    val roadLinkRoadPartNumber = roadLink.attributes.get(RoadPartNumber).map(toIntNumber).getOrElse(0)
//    val VVHRoadName = getVVHRoadName(roadLink.attributes)
//    val municipalityCode = roadLink.attributes.getOrElse(MunicipalityCode, 0) match { // TODO: Simplify this after vvh not used anymore.
//      case m:String => m.asInstanceOf[String].toInt
//      case m:Int => m.asInstanceOf[Int].intValue()
//    }
//    val municipalityName = municipalityNamesMapping.getOrElse(municipalityCode, "")
//    val administrativeClass = unaddressedRoadLink.administrativeClass match {
//      case AdministrativeClass.Unknown => roadLink.administrativeClass
//      case _ => unaddressedRoadLink.administrativeClass
//    }
//    RoadAddressLink(0, 0, roadLink.linkId, geom, length, roadLink.administrativeClass, UnknownLinkType, roadLink.lifecycleStatus, roadLink.linkSource, administrativeClass, VVHRoadName, Some(""), municipalityCode, municipalityName, extractModifiedAt(roadLink.attributes), Some("kgv_modified"), roadLink.attributes, unaddressedRoadLink.roadNumber.getOrElse(roadLinkRoadNumber), unaddressedRoadLink.roadPartNumber.getOrElse(roadLinkRoadPartNumber), Track.Unknown.value, municipalityRoadMaintainerMapping.getOrElse(roadLink.municipalityCode, -1), Discontinuity.Continuous.value, 0, 0, "", "", 0.0, length, SideCode.Unknown, None, None, unaddressedRoadLink.anomaly, newGeometry = Some(roadLink.geometry))
//  }

  private def getVVHRoadName(link: Map[String, Any]): Option[String] = {
    Some(link.getOrElse(FinnishRoadName, link.getOrElse(SwedishRoadName, "none")).toString)
  }
}

// TIETYYPPI (1= yleinen tie, 2 = lauttaväylä yleisellä tiellä, 3 = kunnan katuosuus, 4 = yleisen tien työmaa, 5 = yksityistie, 9 = omistaja selvittämättä)
//sealed trait AdministrativeClass {
//  def value: Int
//
//  def displayValue: String
//}

//object AdministrativeClass {
//  val values = Set(Empty, PublicRoad, FerryRoad, MunicipalityStreetRoad, PublicUnderConstructionRoad, PrivateRoadType, UnknownOwnerRoad)
//
//  def apply(intValue: Int): AdministrativeClass = {
//    values.find(_.value == intValue).getOrElse(UnknownOwnerRoad)
//  }
//
//  case object Empty extends AdministrativeClass {
//    def value = 0
//
//    def displayValue = "--"
//  }
//  case object PublicRoad extends AdministrativeClass {
//    def value = 1
//
//    def displayValue = "Yleinen tie"
//  }
//
//  case object FerryRoad extends AdministrativeClass {
//    def value = 2
//
//    def displayValue = "Lauttaväylä yleisellä tiellä"
//  }
//
//  case object MunicipalityStreetRoad extends AdministrativeClass {
//    def value = 3
//
//    def displayValue = "Kunnan katuosuus"
//  }
//
//  case object PublicUnderConstructionRoad extends AdministrativeClass {
//    def value = 4
//
//    def displayValue = "Yleisen tien työmaa"
//  }
//
//  case object PrivateRoadType extends AdministrativeClass {
//    def value = 5
//
//    def displayValue = "Yksityistie"
//  }
//
//  case object UnknownOwnerRoad extends AdministrativeClass {
//    def value = 9
//
//    def displayValue = "Omistaja selvittämättä"
//  }
//
//  case object Unknown extends AdministrativeClass {
//    def value = 99
//
//    def displayValue = "Ei määritelty"
//  }
//
//}

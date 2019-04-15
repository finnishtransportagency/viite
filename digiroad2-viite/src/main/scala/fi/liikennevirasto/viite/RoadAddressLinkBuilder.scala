package fi.liikennevirasto.viite

import java.util.Properties

import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.asset.LinkGeomSource.{Unknown => _, apply => _}
import fi.liikennevirasto.digiroad2.asset.{LinkGeomSource, _}
import fi.liikennevirasto.digiroad2.client.vvh.{VVHClient, VVHHistoryRoadLink, VVHRoadlink}
import fi.liikennevirasto.digiroad2.linearasset.{RoadLink, RoadLinkLike}
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.model.{Anomaly, RoadAddressLink}
import fi.liikennevirasto.viite.process.RoadwayAddressMapper

class RoadAddressLinkBuilder(roadwayDAO: RoadwayDAO, linearLocationDAO: LinearLocationDAO, projectLinkDAO: ProjectLinkDAO) extends AddressLinkBuilder {
  lazy val properties: Properties = {
    val props = new Properties()
    props.load(getClass.getResourceAsStream("/digiroad2.properties"))
    props
  }

  val vvhClient = new VVHClient(properties.getProperty("digiroad2.VVHRestApiEndPoint"))
  val eventBus = new DummyEventBus
  val linkService = new RoadLinkService(vvhClient, eventBus, new DummySerializer)
  val roadAddressService = new RoadAddressService(linkService, roadwayDAO, linearLocationDAO, new RoadNetworkDAO, new RoadwayAddressMapper(roadwayDAO, linearLocationDAO), eventBus, properties.getProperty("digiroad2.VVHRoadlink.frozen", "false").toBoolean){
    override def withDynSession[T](f: => T): T = f
    override def withDynTransaction[T](f: => T): T = f
  }

  val roadwayAddressMapper = new RoadwayAddressMapper(roadwayDAO, linearLocationDAO)

  def build(roadLink: RoadLinkLike, roadAddress: RoadAddress): RoadAddressLink = {
    val geom = GeometryUtils.truncateGeometry3D(roadLink.geometry, roadAddress.startMValue, roadAddress.endMValue)
    val length = GeometryUtils.geometryLength(geom)
    val VVHRoadName = getVVHRoadName(roadLink.attributes)
    val roadName = roadAddress.roadName
    val municipalityCode = roadLink.attributes.getOrElse(MunicipalityCode, 0).asInstanceOf[Number].intValue()
    val roadType = roadAddress.roadType match {
      case RoadType.Unknown => getRoadType(roadLink.administrativeClass, UnknownLinkType)
      case _ => roadAddress.roadType
    }
    RoadAddressLink(roadAddress.id, roadAddress.linearLocationId, roadLink.linkId, geom,
      length, roadLink.administrativeClass, UnknownLinkType, roadLink.constructionType, roadLink.linkSource, roadType, VVHRoadName, roadName, municipalityCode, extractModifiedAtVVH(roadLink.attributes), Some("vvh_modified"),
      roadLink.attributes, roadAddress.roadNumber, roadAddress.roadPartNumber, roadAddress.track.value, roadAddress.ely, roadAddress.discontinuity.value,
      roadAddress.startAddrMValue, roadAddress.endAddrMValue, roadAddress.startDate.map(formatter.print).getOrElse(""), roadAddress.endDate.map(formatter.print).getOrElse(""), roadAddress.startMValue, roadAddress.endMValue,
      roadAddress.sideCode,
      roadAddress.calibrationPoints._1,
      roadAddress.calibrationPoints._2, Anomaly.None, roadAddress.roadwayNumber)
  }

  def build(roadAddress: RoadAddress): RoadAddressLink = {
    val geom = roadAddress.geometry
    val length = GeometryUtils.geometryLength(geom)
    val municipalityCode = 0
    val roadType = roadAddress.roadType
    RoadAddressLink(roadAddress.id, roadAddress.linearLocationId, roadAddress.linkId, geom,
      length, AdministrativeClass.apply(1), LinkType.apply(99), ConstructionType.apply(0), LinkGeomSource.apply(1), roadType, Some(""), roadAddress.roadName, municipalityCode, Some(""), Some("vvh_modified"),
      Map(), roadAddress.roadNumber, roadAddress.roadPartNumber, roadAddress.track.value, 0, roadAddress.discontinuity.value,
      roadAddress.startAddrMValue, roadAddress.endAddrMValue, roadAddress.startDate.map(formatter.print).getOrElse(""), roadAddress.endDate.map(formatter.print).getOrElse(""), roadAddress.startMValue, roadAddress.endMValue,
      roadAddress.sideCode,
      roadAddress.calibrationPoints._1,
      roadAddress.calibrationPoints._2, Anomaly.None, roadAddress.roadwayNumber)
  }

  /**
    * This will return a RoadAddressLink based on the information of suravage links we fetch from VVH.
    * It is possible that we already have road addresses or project links formed using those suravage links if so we include that information on the final RoadAddressLink.
    * @param roadLinkProjectIdTuple: (VVHRoadlink, Option[Long]) - The suravage link and a possible project id
    * @return
    */
  def buildSuravageRoadAddressLink(roadLinkProjectIdTuple: (VVHRoadlink, Option[Long])): RoadAddressLink = {
    val roadLink = roadLinkProjectIdTuple._1
    val roadAddresses = roadLinkProjectIdTuple._2 match { //Check if project attribute has been initialized
      case Some(projectId) =>
        //TODO define and use projectService to fetch project links by linkId
        projectLinkDAO.getProjectLinksByLinkId(roadLink.linkId)

      case _ =>
        roadAddressService.getRoadAddressesByLinkIds(Seq(roadLink.linkId))
    }
    val headAddress = roadAddresses.headOption
    val geom = GeometryUtils.truncateGeometry3D(roadLink.geometry, 0.0, roadLink.length)
    val length = GeometryUtils.geometryLength(geom)
    val sideCode = headAddress match {
      case Some(road) => road.sideCode
      case _ => SideCode.Unknown
    }
    val startAddrM = if (roadAddresses.nonEmpty) {
      roadAddresses.map(_.startAddrMValue).min
    } else {
      0L
    }
    val endAddrM = if (roadAddresses.nonEmpty) {
      roadAddresses.map(_.endAddrMValue).max
    } else {
      0L
    }

    val roadLinkRoadNumber = toLongNumber(headAddress.map(_.roadNumber), roadLink.attributes.get(RoadNumber))
    val roadLinkRoadPartNumber = toLongNumber(headAddress.map(_.roadPartNumber), roadLink.attributes.get(RoadPartNumber))
    val VVHRoadName = getVVHRoadName(roadLink.attributes)
    val municipalityCode = roadLink.municipalityCode
    val roadNames = RoadNameDAO.getLatestRoadName(roadLinkRoadNumber)

    val roadName = if (roadNames.isEmpty) Some("") else Some(roadNames.get.roadName)

    val anomalyType = {
      if (roadLinkRoadNumber != 0 && roadLinkRoadPartNumber != 0) Anomaly.None else Anomaly.NoAddressGiven
    }
    val trackValue = headAddress match {
      case Some(add) =>
        if (add.linkGeomSource == LinkGeomSource.SuravageLinkInterface) {
          add.track.value
        } else {
          roadLink.attributes.getOrElse("TRACK", Track.Unknown.value).toString.toInt
        }
      case _ => roadLink.attributes.getOrElse("TRACK", Track.Unknown.value).toString.toInt
    }

    val elyCode: Long = headAddress match {
      case Some(add) => add.ely
      case _ => municipalityRoadMaintainerMapping.getOrElse(roadLink.municipalityCode, -1)
    }
    RoadAddressLink(toLongNumber(headAddress.map(_.id), Some(0)), toLongNumber(headAddress.map(_.linearLocationId), Some(0)), roadLink.linkId, geom,
      length, roadLink.administrativeClass, getLinkType(roadLink), roadLink.constructionType,
      roadLink.linkSource, getRoadType(roadLink.administrativeClass, getLinkType(roadLink)),
      VVHRoadName, roadName, municipalityCode, extractModifiedAtVVH(roadLink.attributes), Some("vvh_modified"),
      roadLink.attributes, roadLinkRoadNumber, roadLinkRoadPartNumber, trackValue, elyCode, Discontinuity.Continuous.value,
      startAddrM, endAddrM, "", "", 0.0, length, sideCode, None, None, anomalyType)
  }

  private def getVVHRoadName(link: Map[String, Any]): Option[String] = {
    Some(link.getOrElse(FinnishRoadName, link.getOrElse(SwedishRoadName, "none")).toString)
  }
}

// TIETYYPPI (1= yleinen tie, 2 = lauttaväylä yleisellä tiellä, 3 = kunnan katuosuus, 4 = yleisen tien työmaa, 5 = yksityistie, 9 = omistaja selvittämättä)
sealed trait RoadType {
  def value: Int

  def displayValue: String
}

object RoadType {
  val values = Set(PublicRoad, FerryRoad, MunicipalityStreetRoad, PublicUnderConstructionRoad, PrivateRoadType, UnknownOwnerRoad)

  def apply(intValue: Int): RoadType = {
    values.find(_.value == intValue).getOrElse(UnknownOwnerRoad)
  }

  case object PublicRoad extends RoadType {
    def value = 1

    def displayValue = "Yleinen tie"
  }

  case object FerryRoad extends RoadType {
    def value = 2

    def displayValue = "Lauttaväylä yleisellä tiellä"
  }

  case object MunicipalityStreetRoad extends RoadType {
    def value = 3

    def displayValue = "Kunnan katuosuus"
  }

  case object PublicUnderConstructionRoad extends RoadType {
    def value = 4

    def displayValue = "Yleisen tien työmaa"
  }

  case object PrivateRoadType extends RoadType {
    def value = 5

    def displayValue = "Yksityistie"
  }

  case object UnknownOwnerRoad extends RoadType {
    def value = 9

    def displayValue = "Omistaja selvittämättä"
  }

  case object Unknown extends RoadType {
    def value = 99

    def displayValue = "Ei määritelty"
  }

}

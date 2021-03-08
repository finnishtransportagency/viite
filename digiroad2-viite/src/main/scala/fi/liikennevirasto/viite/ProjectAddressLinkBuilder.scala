package fi.liikennevirasto.viite

import fi.liikennevirasto.GeometryUtils
import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.linearasset.{RoadLink, RoadLinkLike}
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.model.{Anomaly, ProjectAddressLink, RoadAddressLinkLike}
import fi.liikennevirasto.viite.util.CalibrationPointsUtils

object ProjectAddressLinkBuilder extends AddressLinkBuilder {

  def build(pl: ProjectLink, splitPart: Option[ProjectLink] = None): ProjectAddressLink = {

    val linkType = UnknownLinkType

    val originalGeometry =
      if (pl.isSplit)
        if (splitPart.nonEmpty)
          combineGeometries(pl, splitPart.get)
        else
          // TODO Is this case needed?
          Some(pl.geometry)
      else
        None

    val calibrationPoints = pl.calibrationPoints

    ProjectAddressLink(pl.id, pl.linkId, pl.geometry, pl.geometryLength, fi.liikennevirasto.digiroad2.asset.AdministrativeClass.Unknown, linkType, ConstructionType.UnknownConstructionType, pl.linkGeomSource, pl.administrativeClass, pl.roadName, pl.roadName, 0L, "", None, Some("vvh_modified"), Map(), pl.roadNumber, pl.roadPartNumber, pl.track.value, pl.ely, pl.discontinuity.value, pl.startAddrMValue, pl.endAddrMValue, pl.startMValue, pl.endMValue, pl.sideCode, calibrationPoints._1, calibrationPoints._2, Anomaly.None, pl.status, pl.roadwayId, pl.linearLocationId, pl.reversed, pl.connectedLinkId, originalGeometry)
  }

  @Deprecated
  def build(roadLink: RoadLinkLike, projectLink: ProjectLink): ProjectAddressLink = {

    val geom = if (projectLink.isSplit)
      GeometryUtils.truncateGeometry3D(roadLink.geometry, projectLink.startMValue, projectLink.endMValue)
    else
      roadLink.geometry
    val length = GeometryUtils.geometryLength(geom)
    val roadNumber = projectLink.roadNumber match {
      case 0 => roadLink.attributes.getOrElse(RoadNumber, projectLink.roadNumber).asInstanceOf[Number].longValue()
      case _ => projectLink.roadNumber
    }
    val roadPartNumber = projectLink.roadPartNumber match {
      case 0 => roadLink.attributes.getOrElse(RoadPartNumber, projectLink.roadPartNumber).asInstanceOf[Number].longValue()
      case _ => projectLink.roadPartNumber
    }
    val trackCode = projectLink.track.value match {
      case 99 => roadLink.attributes.getOrElse(TrackCode, projectLink.track.value).asInstanceOf[Number].intValue()
      case _ => projectLink.track.value
    }

    val roadName = projectLink.roadName.getOrElse("")
    val municipalityCode = roadLink.municipalityCode

    val linkType = roadLink match {
      case rl: RoadLink => rl.linkType
      case _ => UnknownLinkType
    }

    val originalGeometry =
      if (projectLink.isSplit)
        Some(roadLink.geometry)
      else
        None

    val calibrationPoints = projectLink.calibrationPoints

    build(roadLink, projectLink.id, geom, length, roadNumber, roadPartNumber, trackCode, Some(roadName), municipalityCode,
      linkType, projectLink.administrativeClass, projectLink.discontinuity, projectLink.startAddrMValue, projectLink.endAddrMValue,
      projectLink.startMValue, projectLink.endMValue, projectLink.sideCode,
      calibrationPoints._1, calibrationPoints._2,
      Anomaly.None, projectLink.status, projectLink.roadwayId, projectLink.linearLocationId, projectLink.ely, projectLink.reversed, projectLink.connectedLinkId,
      originalGeometry
    )
  }

  def build(ral: RoadAddressLinkLike): ProjectAddressLink = {
    ProjectAddressLink(ral.id, ral.linkId, ral.geometry, ral.length, ral.administrativeClassMML, ral.linkType, ral.constructionType, ral.roadLinkSource, ral.administrativeClass, ral.VVHRoadName, ral.roadName, ral.municipalityCode, ral.municipalityName, ral.modifiedAt, ral.modifiedBy, ral.attributes, ral.roadNumber, ral.roadPartNumber, ral.trackCode, ral.elyCode, ral.discontinuity, ral.startAddressM, ral.endAddressM, ral.startMValue, ral.endMValue, ral.sideCode, ral.startCalibrationPoint, ral.endCalibrationPoint, ral.anomaly, LinkStatus.Unknown, ral.id, ral.linearLocationId)
  }


  private def build(roadLink: RoadLinkLike, id: Long, geom: Seq[Point], length: Double, roadNumber: Long, roadPartNumber: Long,
                    trackCode: Int, roadName: Option[String], municipalityCode: Int, linkType: LinkType,
                    roadType: AdministrativeClass, discontinuity: Discontinuity,
                    startAddrMValue: Long, endAddrMValue: Long, startMValue: Double, endMValue: Double,
                    sideCode: SideCode, startCalibrationPoint: Option[CalibrationPoint], endCalibrationPoint: Option[CalibrationPoint],
                    anomaly: Anomaly, status: LinkStatus, roadwayId: Long, linearLocationId: Long, ely: Long, reversed: Boolean, connectedLinkId: Option[Long],
                    originalGeometry: Option[Seq[Point]]): ProjectAddressLink = {

    val linkId =
      if (connectedLinkId.nonEmpty && status == LinkStatus.New)
        0L - roadLink.linkId
      else
        roadLink.linkId

    val municipalityName = municipalityNamesMapping.getOrElse(municipalityCode, "")
    ProjectAddressLink(id, linkId, geom, length, roadLink.administrativeClass, linkType, roadLink.constructionType, roadLink.linkSource, roadType, Some(roadLink.attributes.getOrElse(FinnishRoadName, roadLink.attributes.getOrElse(SwedishRoadName, "none")).toString), roadName, municipalityCode, municipalityName, extractModifiedAtVVH(roadLink.attributes), Some("vvh_modified"), roadLink.attributes, roadNumber, roadPartNumber, trackCode, ely, discontinuity.value, startAddrMValue, endAddrMValue, startMValue, endMValue, sideCode, startCalibrationPoint, endCalibrationPoint, anomaly, status, roadwayId, linearLocationId, reversed, connectedLinkId, originalGeometry)
  }

  private def combineGeometries(split1: ProjectLink, split2: ProjectLink) = {
    def safeTail(seq: Seq[Point]) = {
      if (seq.isEmpty)
        Seq()
      else
        seq.tail
    }

    if (split1.startMValue < split2.startMValue)
      Some(split1.geometry ++ safeTail(split2.geometry))
    else
      Some(split2.geometry ++ safeTail(split1.geometry))
  }
}

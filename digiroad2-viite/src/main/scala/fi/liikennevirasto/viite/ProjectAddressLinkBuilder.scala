package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.{GeometryUtils, Point}
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.linearasset.{RoadLink, RoadLinkLike}
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.model.{Anomaly, ProjectAddressLink, RoadAddressLinkLike}

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

    val calibrationPoints = pl.toCalibrationPoints

    ProjectAddressLink(pl.id, pl.linkId, pl.geometry,
      pl.geometryLength, fi.liikennevirasto.digiroad2.asset.Unknown, linkType, ConstructionType.UnknownConstructionType,
      pl.linkGeomSource, pl.roadType, pl.roadName, pl.roadName, 0L, None, Some("vvh_modified"),
      Map(), pl.roadNumber, pl.roadPartNumber, pl.track.value, pl.ely, pl.discontinuity.value,
      pl.startAddrMValue, pl.endAddrMValue, pl.startMValue, pl.endMValue, pl.sideCode, calibrationPoints._1,
      calibrationPoints._2, Anomaly.None, pl.status, pl.roadwayId, pl.linearLocationId,
      pl.reversed, pl.connectedLinkId, originalGeometry)
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

    val calibrationPoints = projectLink.toCalibrationPoints

    build(roadLink, projectLink.id, geom, length, roadNumber, roadPartNumber, trackCode, Some(roadName), municipalityCode,
      linkType, projectLink.roadType, projectLink.discontinuity, projectLink.startAddrMValue, projectLink.endAddrMValue,
      projectLink.startMValue, projectLink.endMValue, projectLink.sideCode,
      calibrationPoints._1, calibrationPoints._2,
      Anomaly.None, projectLink.status, projectLink.roadwayId, projectLink.linearLocationId, projectLink.ely, projectLink.reversed, projectLink.connectedLinkId,
      originalGeometry
    )
  }

  def build(roadLink: RoadLinkLike, unaddressedRoadLink: UnaddressedRoadLink): ProjectAddressLink = {
    val geom = GeometryUtils.truncateGeometry3D(roadLink.geometry, unaddressedRoadLink.startMValue.getOrElse(0.0), unaddressedRoadLink.endMValue.getOrElse(roadLink.length))
    val length = GeometryUtils.geometryLength(geom)
    val roadLinkRoadNumber: Long = roadLink.attributes.get(RoadNumber).map(toLongNumber).getOrElse(0L)
    val roadLinkRoadPartNumber: Long = roadLink.attributes.get(RoadPartNumber).map(toLongNumber).getOrElse(0L)
    val roadLinkTrackCode: Int = roadLink.attributes.get(TrackCode).map(toIntNumber).getOrElse(0)
    val roadName = roadLink.attributes.getOrElse(FinnishRoadName, roadLink.attributes.getOrElse(SwedishRoadName, "none")).toString
    val municipalityCode = roadLink.municipalityCode
    val linkType = roadLink match {
      case rl: RoadLink => rl.linkType
      case _ => UnknownLinkType
    }
    build(roadLink, 0L, geom, length, roadLinkRoadNumber, roadLinkRoadPartNumber, roadLinkTrackCode, Some(roadName), municipalityCode,
      linkType, getRoadType(roadLink.administrativeClass, linkType), Discontinuity.Continuous, unaddressedRoadLink.startAddrMValue.getOrElse(0), unaddressedRoadLink.endAddrMValue.getOrElse(0),
      unaddressedRoadLink.startMValue.getOrElse(0.0), unaddressedRoadLink.endMValue.getOrElse(0.0), SideCode.Unknown,
      None, None, Anomaly.None, LinkStatus.Unknown, 0, 0, municipalityRoadMaintainerMapping.getOrElse(roadLink.municipalityCode, -1), reversed = false, None, None)
  }

  def build(ral: RoadAddressLinkLike): ProjectAddressLink = {
    ProjectAddressLink(ral.id, ral.linkId, ral.geometry, ral.length, ral.administrativeClass, ral.linkType,
      ral.constructionType, ral.roadLinkSource, ral.roadType, ral.VVHRoadName, ral.roadName, ral.municipalityCode, ral.modifiedAt, ral.modifiedBy,
      ral.attributes, ral.roadNumber, ral.roadPartNumber, ral.trackCode, ral.elyCode, ral.discontinuity,
      ral.startAddressM, ral.endAddressM, ral.startMValue, ral.endMValue, ral.sideCode, ral.startCalibrationPoint, ral.endCalibrationPoint,
      ral.anomaly, LinkStatus.Unknown, ral.id, ral.linearLocationId)
  }


  private def build(roadLink: RoadLinkLike, id: Long, geom: Seq[Point], length: Double, roadNumber: Long, roadPartNumber: Long,
                    trackCode: Int, roadName: Option[String], municipalityCode: Int, linkType: LinkType,
                    roadType: RoadType, discontinuity: Discontinuity,
                    startAddrMValue: Long, endAddrMValue: Long, startMValue: Double, endMValue: Double,
                    sideCode: SideCode, startCalibrationPoint: Option[CalibrationPoint], endCalibrationPoint: Option[CalibrationPoint],
                    anomaly: Anomaly, status: LinkStatus, roadwayId: Long, linearLocationId: Long, ely: Long, reversed: Boolean, connectedLinkId: Option[Long],
                    originalGeometry: Option[Seq[Point]]): ProjectAddressLink = {

    val linkId =
      if (connectedLinkId.nonEmpty && status == LinkStatus.New)
        0L - roadLink.linkId
      else
        roadLink.linkId
    ProjectAddressLink(id, linkId, geom,
      length, roadLink.administrativeClass, linkType, roadLink.constructionType, roadLink.linkSource,
      roadType, Some(roadLink.attributes.getOrElse(FinnishRoadName, roadLink.attributes.getOrElse(SwedishRoadName, "none")).toString), roadName, municipalityCode, extractModifiedAtVVH(roadLink.attributes), Some("vvh_modified"),
      roadLink.attributes, roadNumber, roadPartNumber, trackCode, ely, discontinuity.value,
      startAddrMValue, endAddrMValue, startMValue, endMValue, sideCode, startCalibrationPoint, endCalibrationPoint, anomaly, status, roadwayId, linearLocationId,
      reversed, connectedLinkId, originalGeometry)
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

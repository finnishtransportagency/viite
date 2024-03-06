package fi.liikennevirasto.viite

import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.model.{ProjectAddressLink, RoadAddressLinkLike}
import fi.vaylavirasto.viite.geometry.{GeometryUtils, Point}
import fi.vaylavirasto.viite.model.{AddrMRange, AdministrativeClass, Discontinuity, LifecycleStatus, RoadAddressChangeType, RoadLinkLike, RoadPart, SideCode}

object ProjectAddressLinkBuilder extends AddressLinkBuilder {

  def build(pl: ProjectLink, splitPart: Option[ProjectLink] = None): ProjectAddressLink = {

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

    ProjectAddressLink(pl.id, pl.linkId, pl.geometry, pl.geometryLength, AdministrativeClass.Unknown, LifecycleStatus.UnknownLifecycleStatus, pl.linkGeomSource, pl.administrativeClass, pl.roadName, 0L, "", None, Some("vvh_modified"), pl.roadPart, pl.track.value, pl.ely, pl.discontinuity.value, pl.addrMRange, pl.startMValue, pl.endMValue, pl.sideCode, calibrationPoints._1, calibrationPoints._2, pl.status, pl.roadwayId, pl.linearLocationId, pl.reversed, pl.connectedLinkId, originalGeometry, sourceId = "", roadAddressRoadPart = pl.roadAddressRoadPart)
  }

  @Deprecated
  def build(roadLink: RoadLinkLike, projectLink: ProjectLink): ProjectAddressLink = {

    val geom = if (projectLink.isSplit)
      GeometryUtils.truncateGeometry3D(roadLink.geometry, projectLink.startMValue, projectLink.endMValue)
    else
      roadLink.geometry
    val length = GeometryUtils.geometryLength(geom)
    val roadPart = projectLink.roadPart
    val trackCode = projectLink.track.value

    val roadName = projectLink.roadName.getOrElse("")
    val municipalityCode = roadLink.municipalityCode

    val originalGeometry =
      if (projectLink.isSplit)
        Some(roadLink.geometry)
      else
        None

    val calibrationPoints = projectLink.calibrationPoints

    build(roadLink, projectLink.id, geom, length, roadPart, trackCode, Some(roadName), municipalityCode, projectLink.administrativeClass, projectLink.discontinuity, projectLink.addrMRange, projectLink.startMValue, projectLink.endMValue, projectLink.sideCode, calibrationPoints._1, calibrationPoints._2, projectLink.status, projectLink.roadwayId, projectLink.linearLocationId, projectLink.ely, projectLink.reversed, projectLink.connectedLinkId, originalGeometry)
  }

  def build(ral: RoadAddressLinkLike): ProjectAddressLink = {
    ProjectAddressLink(ral.id, ral.linkId, ral.geometry, ral.length, ral.administrativeClassMML, ral.lifecycleStatus, ral.roadLinkSource, ral.administrativeClass, ral.roadName, ral.municipalityCode, ral.municipalityName, ral.modifiedAt, ral.modifiedBy, ral.roadPart, ral.trackCode, ral.elyCode, ral.discontinuity, ral.addrMRange, ral.startMValue, ral.endMValue, ral.sideCode, ral.startCalibrationPoint, ral.endCalibrationPoint, RoadAddressChangeType.Unknown, ral.id, ral.linearLocationId, sourceId = "")
  }


  private def build(roadLink: RoadLinkLike, id: Long, geom: Seq[Point], length: Double, roadPart: RoadPart, trackCode: Int, roadName: Option[String], municipalityCode: Int, administrativeClass: AdministrativeClass, discontinuity: Discontinuity, addrMRange: AddrMRange, startMValue: Double, endMValue: Double, sideCode: SideCode, startCalibrationPoint: Option[ProjectCalibrationPoint], endCalibrationPoint: Option[ProjectCalibrationPoint], status: RoadAddressChangeType, roadwayId: Long, linearLocationId: Long, ely: Long, reversed: Boolean, connectedLinkId: Option[String], originalGeometry: Option[Seq[Point]]): ProjectAddressLink = {

    val linkId =
      if (connectedLinkId.nonEmpty && status == RoadAddressChangeType.New)
        "-" + roadLink.linkId
      else
        roadLink.linkId

    val municipalityName = municipalityNamesMapping.getOrElse(municipalityCode, "")

    ProjectAddressLink(id, linkId, geom, length, roadLink.administrativeClass, roadLink.lifecycleStatus, roadLink.linkSource, administrativeClass, roadName, municipalityCode, municipalityName, roadLink.modifiedAt, Some("kgv_modified"), roadPart, trackCode, ely, discontinuity.value, addrMRange, startMValue, endMValue, sideCode, startCalibrationPoint, endCalibrationPoint, status, roadwayId, linearLocationId, reversed, connectedLinkId, originalGeometry, sourceId = "")
  }

  private def combineGeometries(split1: ProjectLink, split2: ProjectLink): Option[Seq[Point]] = {
    def safeTail(seq: Seq[Point]): Seq[Point] = {
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

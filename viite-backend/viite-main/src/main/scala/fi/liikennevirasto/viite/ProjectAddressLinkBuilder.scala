package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.{GeometryUtils, Point}
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.linearasset.RoadLinkLike
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.model.{ProjectAddressLink, RoadAddressLinkLike}

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

    ProjectAddressLink(pl.id, pl.linkId, pl.geometry, pl.geometryLength, fi.liikennevirasto.digiroad2.asset.AdministrativeClass.Unknown, LifecycleStatus.UnknownLifecycleStatus, pl.linkGeomSource, pl.administrativeClass, pl.roadName, 0L, "", None, Some("vvh_modified"), pl.roadNumber, pl.roadPartNumber, pl.track.value, pl.ely, pl.discontinuity.value, pl.startAddrMValue, pl.endAddrMValue, pl.startMValue, pl.endMValue, pl.sideCode, calibrationPoints._1, calibrationPoints._2, pl.status, pl.roadwayId, pl.linearLocationId, pl.reversed, pl.connectedLinkId, originalGeometry, sourceId = "", roadAddressRoadNumber = pl.roadAddressRoadNumber, roadAddressRoadPart = pl.roadAddressRoadPart)
  }

  @Deprecated
  def build(roadLink: RoadLinkLike, projectLink: ProjectLink): ProjectAddressLink = {

    val geom = if (projectLink.isSplit)
      GeometryUtils.truncateGeometry3D(roadLink.geometry, projectLink.startMValue, projectLink.endMValue)
    else
      roadLink.geometry
    val length = GeometryUtils.geometryLength(geom)
    val roadNumber = projectLink.roadNumber
    val roadPartNumber = projectLink.roadPartNumber
    val trackCode = projectLink.track.value

    val roadName = projectLink.roadName.getOrElse("")
    val municipalityCode = roadLink.municipalityCode

    val originalGeometry =
      if (projectLink.isSplit)
        Some(roadLink.geometry)
      else
        None

    val calibrationPoints = projectLink.calibrationPoints

    build(roadLink, projectLink.id, geom, length, roadNumber, roadPartNumber, trackCode, Some(roadName), municipalityCode, projectLink.administrativeClass, projectLink.discontinuity, projectLink.startAddrMValue, projectLink.endAddrMValue, projectLink.startMValue, projectLink.endMValue, projectLink.sideCode, calibrationPoints._1, calibrationPoints._2, projectLink.status, projectLink.roadwayId, projectLink.linearLocationId, projectLink.ely, projectLink.reversed, projectLink.connectedLinkId, originalGeometry)
  }

  def build(ral: RoadAddressLinkLike): ProjectAddressLink = {
    ProjectAddressLink(ral.id, ral.linkId, ral.geometry, ral.length, ral.administrativeClassMML, ral.lifecycleStatus, ral.roadLinkSource, ral.administrativeClass, ral.roadName, ral.municipalityCode, ral.municipalityName, ral.modifiedAt, ral.modifiedBy, ral.roadNumber, ral.roadPartNumber, ral.trackCode, ral.elyCode, ral.discontinuity, ral.startAddressM, ral.endAddressM, ral.startMValue, ral.endMValue, ral.sideCode, ral.startCalibrationPoint, ral.endCalibrationPoint, LinkStatus.Unknown, ral.id, ral.linearLocationId, sourceId = "")
  }


  private def build(roadLink: RoadLinkLike, id: Long, geom: Seq[Point], length: Double, roadNumber: Long, roadPartNumber: Long, trackCode: Int, roadName: Option[String], municipalityCode: Int, administrativeClass: AdministrativeClass, discontinuity: Discontinuity, startAddrMValue: Long, endAddrMValue: Long, startMValue: Double, endMValue: Double, sideCode: SideCode, startCalibrationPoint: Option[CalibrationPoint], endCalibrationPoint: Option[CalibrationPoint], status: LinkStatus, roadwayId: Long, linearLocationId: Long, ely: Long, reversed: Boolean, connectedLinkId: Option[String], originalGeometry: Option[Seq[Point]]): ProjectAddressLink = {

    val linkId =
      if (connectedLinkId.nonEmpty && status == LinkStatus.New)
        "-" + roadLink.linkId
      else
        roadLink.linkId

    val municipalityName = municipalityNamesMapping.getOrElse(municipalityCode, "")

    ProjectAddressLink(id, linkId, geom, length, roadLink.administrativeClass, roadLink.lifecycleStatus, roadLink.linkSource, administrativeClass, roadName, municipalityCode, municipalityName, roadLink.modifiedAt, Some("kgv_modified"), roadNumber, roadPartNumber, trackCode, ely, discontinuity.value, startAddrMValue, endAddrMValue, startMValue, endMValue, sideCode, startCalibrationPoint, endCalibrationPoint, status, roadwayId, linearLocationId, reversed, connectedLinkId, originalGeometry, sourceId = "")
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
package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.{GeometryUtils, Point}
import fi.liikennevirasto.digiroad2.asset.State
import fi.liikennevirasto.digiroad2.client.vvh.VVHHistoryRoadLink
import fi.liikennevirasto.digiroad2.linearasset.RoadLinkLike
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite.RoadType.PublicRoad
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.model.{Anomaly, ProjectAddressLink, RoadAddressLink}
import fi.liikennevirasto.viite.{RoadAddressLinkBuilder, _}
import org.slf4j.LoggerFactory


object RoadAddressFiller {

  val logger = LoggerFactory.getLogger(getClass)
  val roadAddressLinkBuilder = new RoadAddressLinkBuilder(new RoadwayDAO, new LinearLocationDAO, new ProjectLinkDAO)

  case class LinearLocationAdjustment(linearLocationId: Long, linkId: Long, startMeasure: Option[Double], endMeasure: Option[Double], geometry: Seq[Point])

  case class ChangeSet(
                      droppedSegmentIds: Set[Long],
                      adjustedMValues: Seq[LinearLocationAdjustment],
                      newLinearLocations: Seq[LinearLocation])

  private def extendToGeometry(roadLink: RoadLinkLike, segments: Seq[ProjectAddressLink]): Seq[ProjectAddressLink] = {
    if (segments.isEmpty || segments.exists(_.connectedLinkId.nonEmpty))
      return segments
    val linkLength = GeometryUtils.geometryLength(roadLink.geometry)
    val sorted = segments.sortBy(_.endMValue)(Ordering[Double].reverse)
    val lastSegment = sorted.head
    val restSegments = sorted.tail
    val allowedDiff = ((linkLength - MaxAllowedMValueError) - lastSegment.endMValue) <= MaxDistanceDiffAllowed
    val adjustments = if ((lastSegment.endMValue < linkLength - MaxAllowedMValueError) && allowedDiff) {
      restSegments ++ Seq(lastSegment.copy(endMValue = linkLength))
    } else {
      segments
    }
    adjustments
  }

  def generateUnknownRoadAddressesForRoadLink(roadLink: RoadLinkLike, adjustedSegments: Seq[RoadAddressLink]): Seq[UnaddressedRoadLink] = {
    if (adjustedSegments.isEmpty)
      generateUnknownLink(roadLink)
    else
      Seq()
  }

  private def isPublicRoad(roadLink: RoadLinkLike) = {
    roadLink.administrativeClass == State || roadLink.attributes.get("ROADNUMBER").exists(_.toString.toInt > 0)
  }

  private def generateUnknownLink(roadLink: RoadLinkLike) = {
    val geom = GeometryUtils.truncateGeometry3D(roadLink.geometry, 0.0, roadLink.length)
    Seq(UnaddressedRoadLink(roadLink.linkId, None, None, PublicRoad, None, None, Some(0.0), Some(roadLink.length), if (isPublicRoad(roadLink)) {
      Anomaly.NoAddressGiven
    } else {
      Anomaly.None
    }, geom))
  }

  def fillProjectTopology(roadLinks: Seq[RoadLinkLike], roadAddressMap: Map[Long, Seq[ProjectAddressLink]]): Seq[ProjectAddressLink] = {
    val fillOperations: Seq[(RoadLinkLike, Seq[ProjectAddressLink]) => Seq[ProjectAddressLink]] = Seq(
      extendToGeometry
    )

    roadLinks.foldLeft(Seq.empty[ProjectAddressLink]) { case (acc, roadLink) =>
      val existingSegments = acc
      val segment = roadAddressMap.getOrElse(roadLink.linkId, Seq())

      val adjustedSegments = fillOperations.foldLeft(segment) { case (currentSegments, operation) =>
        operation(roadLink, currentSegments)
      }
      existingSegments ++ adjustedSegments
    }
  }

  private def dropSegmentsOutsideGeometry(roadLink: RoadLinkLike, segments: Seq[LinearLocation], changeSet: ChangeSet): (Seq[LinearLocation], ChangeSet) = {
    val linkLength = GeometryUtils.geometryLength(roadLink.geometry)
    val (overflowingSegments, passThroughSegments) = segments.partition(x => x.startMValue + Epsilon > linkLength)

    val droppedSegmentIds = overflowingSegments.map(s => s.id)

    (passThroughSegments, changeSet.copy(droppedSegmentIds = changeSet.droppedSegmentIds ++ droppedSegmentIds))
  }

  /**
    * If the linear location segment end measure is bigger that the geometry length + ${MaxDistanceDiffAllowed},
    * the linear location segment is cut to fit the all road link geometry
    *
    * @param roadLink
    * @param segments
    * @param changeSet
    * @return
    */
  private def capToGeometry(roadLink: RoadLinkLike, segments: Seq[LinearLocation], changeSet: ChangeSet): (Seq[LinearLocation], ChangeSet) = {
    val linkLength = GeometryUtils.geometryLength(roadLink.geometry)
    val (overflowingSegments, passThroughSegments) = segments.partition(x => (x.endMValue - MaxAllowedMValueError > linkLength) && (x.endMValue - linkLength <= MaxDistanceDiffAllowed))
    val cappedSegments = overflowingSegments.map { s =>
      val newGeom = GeometryUtils.geometrySeqEndPoints(GeometryUtils.truncateGeometry3D(roadLink.geometry, s.startMValue, linkLength))
      (s.copy(endMValue = linkLength, geometry = newGeom), LinearLocationAdjustment(s.id, roadLink.linkId, None, Option(linkLength), newGeom))
    }
    (passThroughSegments ++ cappedSegments.map(_._1), changeSet.copy(adjustedMValues = changeSet.adjustedMValues ++ cappedSegments.map(_._2)))
  }

  private def extendToGeometry(roadLink: RoadLinkLike, segments: Seq[LinearLocation], changeSet: ChangeSet): (Seq[LinearLocation], ChangeSet) = {
    if (segments.isEmpty)
      return (segments, changeSet)

    val linkLength = GeometryUtils.geometryLength(roadLink.geometry)

    val sorted = segments.sortBy(_.endMValue)
    val lastSegment = sorted.last
    val restSegments = sorted.init

    val (extendedSegments, adjustments) = if ((lastSegment.endMValue < linkLength - MaxAllowedMValueError) && ((linkLength - MaxAllowedMValueError) - lastSegment.endMValue) <= MaxDistanceDiffAllowed) {
      val newGeom = GeometryUtils.geometrySeqEndPoints(GeometryUtils.truncateGeometry3D(roadLink.geometry, lastSegment.startMValue, linkLength))
      (restSegments ++ Seq(lastSegment.copy(endMValue = linkLength, geometry = newGeom)),
        Seq(LinearLocationAdjustment(lastSegment.id, lastSegment.linkId, None, Option(linkLength), newGeom)))
    } else {
      (segments, Seq())
    }

    (extendedSegments, changeSet.copy(adjustedMValues = changeSet.adjustedMValues ++ adjustments))
  }

  /**
    * Drops all the linear locations with length less than ${MinAllowedRoadAddressLength}
    *
    * @param roadLink  The vvh road link
    * @param segments  The linear location on the given road link
    * @param changeSet The resume of changes applied on all the adjust operations
    * @return
    */
  private def dropShort(roadLink: RoadLinkLike, segments: Seq[LinearLocation], changeSet: ChangeSet): (Seq[LinearLocation], ChangeSet) = {
    if (segments.size < 2)
      return (segments, changeSet)

    val (droppedSegments, passThroughSegments) = segments.partition(s => (s.endMValue - s.startMValue) < MinAllowedRoadAddressLength)

    val droppedSegmentIds = droppedSegments.map(_.id).toSet

    (passThroughSegments, changeSet.copy(droppedSegmentIds = changeSet.droppedSegmentIds ++ droppedSegmentIds))
  }

  //TODO can also be done here the fuse of linear locations when thr roadway id of the linear location is the same and no calibration points in the middle
  def adjustToTopology(topology: Seq[RoadLinkLike], linearLocations: Seq[LinearLocation], initialChangeSet: ChangeSet = ChangeSet(Set.empty, Seq.empty, Seq.empty)): (Seq[LinearLocation], ChangeSet) = {
    time(logger, "Adjust linear location to topology") {
      val adjustOperations: Seq[(RoadLinkLike, Seq[LinearLocation], ChangeSet) => (Seq[LinearLocation], ChangeSet)] = Seq(
        dropSegmentsOutsideGeometry,
        capToGeometry,
        extendToGeometry,
        dropShort
      )

      val topologyMap = topology.groupBy(_.linkId)
      val linearLocationMap = linearLocations.groupBy(_.linkId)

      linearLocationMap.foldLeft(Seq.empty[LinearLocation], initialChangeSet) {
        case ((existingSegments, changeSet), (linkId, roadLinkSegments)) =>
          val roadLinkOption = topologyMap.getOrElse(linkId, Seq()).headOption
          if (roadLinkOption.isEmpty) {
            (existingSegments ++ roadLinkSegments, changeSet)
          } else {
            val (ajustedSegments, adjustments) = adjustOperations.foldLeft(roadLinkSegments, changeSet) {
              case ((currentSegments, currentAdjustments), operation) =>
                operation(roadLinkOption.get, currentSegments, currentAdjustments)
            }
            (existingSegments ++ ajustedSegments, adjustments)
          }
      }
    }
  }

  private def generateSegments(topology: RoadLinkLike, roadAddresses: Seq[RoadAddress]): Seq[RoadAddressLink]  = {
    roadAddresses.map(ra => roadAddressLinkBuilder.build(topology, ra))
  }

  def fillTopology(topology: Seq[RoadLinkLike], roadAddresses: Seq[RoadAddress]): Seq[RoadAddressLink] = {
    val fillOperations: Seq[(RoadLinkLike, Seq[RoadAddress]) => Seq[RoadAddressLink]] = Seq(
      generateSegments
    )

    val roadAddressesMap = roadAddresses.groupBy(_.linkId)
    topology.flatMap {
      roadLink =>
        val segments = roadAddressesMap.getOrElse(roadLink.linkId, Seq())
        fillOperations.flatMap(operation => operation(roadLink, segments))
    }
  }
}

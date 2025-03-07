package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite.{RoadAddressLinkBuilder, _}
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.model.RoadAddressLink
import fi.vaylavirasto.viite.dao.UnaddressedRoadLink
import fi.vaylavirasto.viite.geometry.{GeometryUtils, Point}
import fi.vaylavirasto.viite.model.{AdministrativeClass, RoadLinkLike}
import org.slf4j.{Logger, LoggerFactory}


object RoadAddressFiller {

  val logger: Logger = LoggerFactory.getLogger(getClass)
  val roadAddressLinkBuilder = new RoadAddressLinkBuilder(new RoadwayDAO, new LinearLocationDAO)

  case class LinearLocationAdjustment(linearLocationId: Long, linkId: String, startMeasure: Option[Double], endMeasure: Option[Double], geometry: Seq[Point])

  case class ChangeSet(
                      droppedSegmentIds: Set[Long],
                      adjustedMValues: Seq[LinearLocationAdjustment],
                      newLinearLocations: Seq[LinearLocation])

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
    * @param roadLink  The road link
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

  /**
    * Generate unaddressed road address links only for the all road link, missing unaddressed parts of the road link are generated
    * by batch process
    * ATTENTION: We can in the future also crete here unaddressed parts if needed.
    * @param roadLink
    * @param roadAddresses
    * @return
    */
  private def generateUnaddressedSegments(roadLink: RoadLinkLike, roadAddresses: Seq[RoadAddress]): Seq[RoadAddressLink] = {
    //TODO check if its needed to create unaddressed road link for part after VIITE-1536
    if (roadAddresses.isEmpty) {
      val unaddressedRoadLink =
        UnaddressedRoadLink(roadLink.linkId, None, AdministrativeClass.Unknown, None, None, Some(0.0), Some(roadLink.length),
          GeometryUtils.truncateGeometry3D(roadLink.geometry, 0.0, roadLink.length))

      Seq(roadAddressLinkBuilder.build(roadLink, unaddressedRoadLink))
    } else {
      Seq()
    }
  }

  private def generateSegments(topology: RoadLinkLike, roadAddresses: Seq[RoadAddress]): Seq[RoadAddressLink]  = {
    roadAddresses.map(ra => roadAddressLinkBuilder.build(topology, ra))
  }

  def fillTopology(topology: Seq[RoadLinkLike], roadAddresses: Seq[RoadAddress]): Seq[RoadAddressLink] = {
    val fillOperations: Seq[(RoadLinkLike, Seq[RoadAddress]) => Seq[RoadAddressLink]] = Seq(
      generateUnaddressedSegments,
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

package fi.liikennevirasto.viite.model

import fi.liikennevirasto.GeometryUtils
import fi.liikennevirasto.digiroad2.asset.LinkGeomSource
import fi.liikennevirasto.digiroad2.linearasset.GraphPartitioner
import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.viite.dao.LinkStatus

object ProjectLinkPartitioner extends GraphPartitioner {

  def partition[T <: ProjectAddressLinkLike](projectLinks: Seq[T]): Seq[Seq[T]] = {
    val (outside, inProject) = projectLinks.partition(_.status == LinkStatus.Unknown)
    val inProjectGroups = inProject.groupBy(l => (l.status, l.roadNumber, l.roadPartNumber, l.trackCode, l.roadType, l.elyCode))
    val (outsideWithRoadName, outsideWithoutRoadName) = outside.partition(link => link.kmtkRoadName.get != "none" && link.kmtkRoadName.get != "" && link.kmtkRoadName.get != " " || (link.roadNumber != 0 && link.roadPartNumber != 0))
    val groupedUnnamedRoads = groupRoadsWithoutName(Seq(), Seq(), outsideWithoutRoadName, outsideWithoutRoadName)
    val outsideGroup = outsideWithRoadName.groupBy(link => (link.roadLinkSource, link.partitioningName))
    val clusters = for (linkGroup <- inProjectGroups.values.toSeq ++ outsideGroup.values.toSeq;
                        cluster <- clusterLinks(linkGroup, fi.liikennevirasto.viite.MaxDistanceForConnectedLinks)) yield cluster
    clusters.map(linksFromCluster) ++ groupedUnnamedRoads
  }

  def groupRoadsWithoutName[T <: ProjectAddressLinkLike](ready: Seq[Seq[T]], prepared: Seq[T], unprocessed: Seq[T], allLinks: Seq[T], pointToConnect: Point = Point(0, 0)): Seq[Seq[T]] = {
    if (unprocessed.isEmpty) {
      ready ++ Seq(prepared)
    } else if (prepared.isEmpty) {
      val initialLink = findNotConnectedLink(unprocessed).getOrElse(unprocessed.head)
      val mappedLinks = ready.flatMap(_.map(_.linkId))
      val filteredLinks = allLinks.filterNot(link => mappedLinks.contains(link.linkId))
      val linksConnectedToPreparedHead = filteredLinks.filter(link => GeometryUtils.areAdjacent(link.geometry, initialLink.geometry.head))
      val linksConnectedToPreparedLast = filteredLinks.filter(link => GeometryUtils.areAdjacent(link.geometry, initialLink.geometry.last))
      if (linksConnectedToPreparedHead.length > linksConnectedToPreparedLast.length)
        groupRoadsWithoutName(ready, Seq(initialLink), unprocessed.filterNot(_.linkId == initialLink.linkId), allLinks, initialLink.geometry.head)
      else
        groupRoadsWithoutName(ready, Seq(initialLink), unprocessed.filterNot(_.linkId == initialLink.linkId), allLinks, initialLink.geometry.last)
    } else {
      val linksConnectedToPrepared = allLinks.filterNot(link => prepared.map(_.linkId).contains(link.linkId)).filter(link => GeometryUtils.areAdjacent(link.geometry, pointToConnect))
      if (linksConnectedToPrepared.lengthCompare(1) == 0) {
        val linkToAdd = linksConnectedToPrepared.head
        if (GeometryUtils.areAdjacent(linkToAdd.geometry.head, pointToConnect))
          groupRoadsWithoutName(ready, prepared ++ Seq(linkToAdd), unprocessed.filterNot(_.linkId == linkToAdd.linkId), allLinks, linkToAdd.geometry.last)
        else
          groupRoadsWithoutName(ready, prepared ++ Seq(linkToAdd), unprocessed.filterNot(_.linkId == linkToAdd.linkId), allLinks, linkToAdd.geometry.head)
      } else {
        groupRoadsWithoutName(ready ++ Seq(prepared), Seq(), unprocessed, allLinks)
      }
    }
  }

  def findNotConnectedLink[T <: ProjectAddressLinkLike](unprocessed: Seq[T]): Option[T] = {
    unprocessed.find(link => {
      !unprocessed.filterNot(_.linkId == link.linkId).flatMap(_.geometry).contains(link.geometry.head, link.geometry.last)
    })
  }
  
}

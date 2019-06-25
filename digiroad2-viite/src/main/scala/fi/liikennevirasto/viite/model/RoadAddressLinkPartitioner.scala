package fi.liikennevirasto.viite.model

import fi.liikennevirasto.digiroad2.asset.LinkGeomSource
import fi.liikennevirasto.digiroad2.linearasset.GraphPartitioner

object RoadAddressLinkPartitioner extends GraphPartitioner {

  def partition[T <: RoadAddressLinkLike](links: Seq[T]): Seq[Seq[T]] = {
    val linkGroups = links.groupBy { link => (
      link.anomaly.equals(Anomaly.NoAddressGiven), link.roadNumber, link.roadPartNumber, link.trackCode,
      link.roadLinkSource.equals(LinkGeomSource.ComplementaryLinkInterface)
      )
    }

    val clusters = for (linkGroup <- linkGroups.values.toSeq;
                        cluster <- clusterLinks(linkGroup, fi.liikennevirasto.viite.MaxDistanceForConnectedLinks)) yield cluster

    clusters.map(linksFromCluster)
  }
}

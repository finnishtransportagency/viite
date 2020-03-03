package fi.liikennevirasto.viite.model

import fi.liikennevirasto.digiroad2.asset.{LifecycleStatus, LinkGeomSource}
import fi.liikennevirasto.digiroad2.linearasset.GraphPartitioner

object RoadAddressLinkPartitioner extends GraphPartitioner {

  /*Homogeneous section: is the selectable sections user can made in the map road network.
    When selecting one link on the map(through single click), we dont want to mix addressed with unaddressed roads, the same way we dont want to mix unaddressed underConstruction with unaddressed constructed roads.
  */
  def groupByHomogeneousSection[T <: RoadAddressLinkLike](links: Seq[T]): Seq[Seq[T]] = {
    val linkGroups = links.groupBy { link => (
      link.anomaly.equals(Anomaly.NoAddressGiven), link.constructionType.equals(LifecycleStatus.UnderConstruction), link.roadNumber, link.roadPartNumber, link.trackCode,
      link.roadLinkSource.equals(LinkGeomSource.ComplementaryLinkInterface)
      )
    }

    val clusters = for (linkGroup <- linkGroups.values.toSeq;
                        cluster <- clusterLinks(linkGroup, fi.liikennevirasto.viite.MaxDistanceForConnectedLinks)) yield cluster

    clusters.map(linksFromCluster)
  }
}

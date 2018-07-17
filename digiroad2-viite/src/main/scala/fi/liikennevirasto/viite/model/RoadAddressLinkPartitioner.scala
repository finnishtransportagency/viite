package fi.liikennevirasto.viite.model

import fi.liikennevirasto.digiroad2.asset.LinkGeomSource
import fi.liikennevirasto.digiroad2.linearasset.GraphPartitioner
import fi.liikennevirasto.digiroad2.service.RoadLinkType

object RoadAddressLinkPartitioner extends GraphPartitioner {

  def partition[T <: RoadAddressLinkLike](links: Seq[T]): Seq[Seq[T]] = {
    val linkGroups = links.groupBy { link => (
      link.anomaly.equals(Anomaly.NoAddressGiven), link.roadNumber, link.roadPartNumber, link.trackCode,
      link.roadLinkType.equals(RoadLinkType.FloatingRoadLinkType), link.roadLinkSource.equals(LinkGeomSource.ComplimentaryLinkInterface), link.roadLinkSource.equals(LinkGeomSource.SuravageLinkInterface)
      )
    }

    def partitionForFloatingsWithCalibration(ready: Seq[Seq[T]], prepared: Seq[T], unprocessed: Seq[T]): Seq[Seq[T]] = {
      if (unprocessed.isEmpty) {
        ready ++ Seq(prepared)
      } else {
        val nextFloating = unprocessed.head
        if (prepared.isEmpty) {
          if (nextFloating.startCalibrationPoint.isDefined && nextFloating.endCalibrationPoint.isDefined)
            partitionForFloatingsWithCalibration(ready ++ Seq(Seq(nextFloating)), Seq(), unprocessed.tail)
          else
            partitionForFloatingsWithCalibration(ready, Seq(unprocessed.head), unprocessed.tail)
        } else {
          if (nextFloating.endCalibrationPoint.isDefined && prepared.nonEmpty)
            partitionForFloatingsWithCalibration(ready ++ Seq(prepared ++ Seq(nextFloating)), Seq(), unprocessed.tail)
          else
            partitionForFloatingsWithCalibration(ready, prepared ++ Seq(nextFloating), unprocessed.tail)
        }
      }
    }

    val groups = linkGroups.map(group =>
      if (group._1._5)
        partitionForFloatingsWithCalibration( Seq[Seq[T]](), Seq(), group._2.sortBy(_.startAddressM)).map(g => (group._1, g))
      else
        Seq(group)
    ).flatten.toSeq

    val clusters = for (linkGroup <- groups;
                        cluster <- clusterLinks(linkGroup._2, fi.liikennevirasto.viite.MaxDistanceForConnectedLinks)) yield cluster

    clusters.map(linksFromCluster)
  }
}

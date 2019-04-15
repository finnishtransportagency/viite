package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.Point

trait RoadAddressMapper {
  case class LinearLocationMapping(sourceLinkId: Long, targetLinkId: Long, sourceId: Long, sourceStartM: Double, sourceEndM: Double,
                                   targetStartM: Double, targetEndM: Double, sourceGeom: Seq[Point], targetGeom: Seq[Point],
                                   vvhTimeStamp: Option[Long] = None) {
    override def toString: String = {
      s"$sourceLinkId -> $targetLinkId: $sourceStartM-$sourceEndM ->  $targetStartM-$targetEndM, $sourceGeom -> $targetGeom"
    }
  }

}

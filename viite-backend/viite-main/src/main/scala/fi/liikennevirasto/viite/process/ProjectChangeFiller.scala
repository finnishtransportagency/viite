package fi.liikennevirasto.viite.process

import fi.liikennevirasto.viite.ProjectRoadLinkChange
import fi.liikennevirasto.viite.dao.{ProjectLink, RoadAddress}
import fi.vaylavirasto.viite.model.{AddrMRange, RoadAddressChangeType}

object ProjectChangeFiller {

  def mapAddressProjectionsToLinks(roadwayLinks: Seq[ProjectLink], projectLinkChanges: Seq[ProjectRoadLinkChange], mappedRoadAddressesProjection: Seq[RoadAddress]): (Seq[ProjectLink], Seq[ProjectRoadLinkChange]) = {
    val (terminatedRoadwayLinks, validRoadwayLinks) = roadwayLinks.partition(_.status == RoadAddressChangeType.Termination)
    val enrichedProjectLinks = validRoadwayLinks.map { l =>
      val ra = mappedRoadAddressesProjection.find(_.linearLocationId == l.linearLocationId)
      if (ra.isDefined)
        Some(l.copy(addrMRange = AddrMRange(ra.get.addrMRange.start, ra.get.addrMRange.end)))
      else
        None
    }.filter(_.isDefined).map(_.get) ++ terminatedRoadwayLinks

    val (terminatedProjectLinkChanges, validProjectLinkChanges) = projectLinkChanges.partition(_.status == RoadAddressChangeType.Termination)
    val enrichedProjectRoadLinkChanges = validProjectLinkChanges.map { rlc =>
      val pl = enrichedProjectLinks.find(_.id == rlc.id)
      if (pl.isDefined) {
        val pl_ = pl.get
        Some(rlc.copy(linearLocationId = pl_.linearLocationId, newStartAddr = pl_.addrMRange.start, newEndAddr = pl_.addrMRange.end))
      }
      else
        None
    }.filter(_.isDefined).map(_.get) ++ terminatedProjectLinkChanges
    (enrichedProjectLinks, enrichedProjectRoadLinkChanges)
  }
}

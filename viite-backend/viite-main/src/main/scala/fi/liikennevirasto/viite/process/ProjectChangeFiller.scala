package fi.liikennevirasto.viite.process

import fi.liikennevirasto.viite.ProjectRoadLinkChange
import fi.liikennevirasto.viite.dao.{ProjectLink, ProjectRoadwayChange, RoadAddress}
import fi.vaylavirasto.viite.model.RoadAddressChangeType

object ProjectChangeFiller {
  def mapLinksToChanges(roadwayChanges: List[ProjectRoadwayChange], roadwayProjectLinkIds: Seq[(Long, Long)], projectLinks: Seq[ProjectLink], projectLinkChanges: Seq[ProjectRoadLinkChange]): Seq[(ProjectRoadwayChange, Seq[ProjectLink])] = {
    roadwayChanges.map {
      change =>
        val linksRelatedToChange = roadwayProjectLinkIds.filter(link => link._1 == change.changeInfo.orderInChangeTable).map(_._2)
        val projectLinksInChange = projectLinks.filter(pl => linksRelatedToChange.contains(pl.id))
        val assignedProperRoadwayNumbers = projectLinksInChange.map { pl =>
          val properRoadwayNumber = projectLinkChanges.find(_.id == pl.id)
          pl.copy(roadwayNumber = properRoadwayNumber.get.newRoadwayNumber)
        }
        (change, assignedProperRoadwayNumbers)
    }
  }

  def mapAddressProjectionsToLinks(roadwayLinks: Seq[ProjectLink], projectLinkChanges: Seq[ProjectRoadLinkChange], mappedRoadAddressesProjection: Seq[RoadAddress]): (Seq[ProjectLink], Seq[ProjectRoadLinkChange]) = {
    val (terminatedRoadwayLinks, validRoadwayLinks) = roadwayLinks.partition(_.status == RoadAddressChangeType.Termination)
    val enrichedProjectLinks = validRoadwayLinks.map { l =>
      val ra = mappedRoadAddressesProjection.find(_.linearLocationId == l.linearLocationId)
      if (ra.isDefined)
        Some(l.copy(startAddrMValue = ra.get.startAddrMValue, endAddrMValue = ra.get.endAddrMValue))
      else
        None
    }.filter(_.isDefined).map(_.get) ++ terminatedRoadwayLinks

    val (terminatedProjectLinkChanges, validProjectLinkChanges) = projectLinkChanges.partition(_.status == RoadAddressChangeType.Termination)
    val enrichedProjectRoadLinkChanges = validProjectLinkChanges.map { rlc =>
      val pl = enrichedProjectLinks.find(_.id == rlc.id)
      if (pl.isDefined) {
        val pl_ = pl.get
        Some(rlc.copy(linearLocationId = pl_.linearLocationId, newStartAddr = pl_.startAddrMValue, newEndAddr = pl_.endAddrMValue))
      }
      else
        None
    }.filter(_.isDefined).map(_.get) ++ terminatedProjectLinkChanges
    (enrichedProjectLinks, enrichedProjectRoadLinkChanges)
  }
}

package fi.liikennevirasto.viite.process

import fi.liikennevirasto.viite.ProjectRoadLinkChange
import fi.liikennevirasto.viite.dao.{LinkStatus, ProjectLink, ProjectRoadwayChange, RoadAddress}

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
    val (terminatedRoadwayLinks, validRoadwayLinks) = roadwayLinks.partition(_.status == LinkStatus.Terminated)
    val enrichedProjectLinks = validRoadwayLinks.map { l =>
      val ra = mappedRoadAddressesProjection.find(_.linearLocationId == l.linearLocationId).get
      l.copy(startAddrMValue = ra.startAddrMValue, endAddrMValue = ra.endAddrMValue)
    } ++ terminatedRoadwayLinks
    val (terminatedProjectLinkChanges, validProjectLinkChanges) = projectLinkChanges.partition(_.status == LinkStatus.Terminated)
    val enrichedProjectRoadLinkChanges = validProjectLinkChanges.map { rlc =>
      val pl: ProjectLink = enrichedProjectLinks.find(_.id == rlc.id).get
      rlc.copy(linearLocationId = pl.linearLocationId, newStartAddr = pl.startAddrMValue, newEndAddr = pl.endAddrMValue)
    } ++ terminatedProjectLinkChanges
    (enrichedProjectLinks, enrichedProjectRoadLinkChanges)
  }
}

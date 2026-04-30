package fi.liikennevirasto.viite.util

import fi.liikennevirasto.viite.dao.ProjectLink
import fi.vaylavirasto.viite.model.{AddrMRange, Track}


// Shared utilities used by termination and administrative class 2-track synchronizers

object SynchronizationUtils {

  // NOTE: If this value is changed, make sure to update the test cases in TwoTrackSectionSynchronizerSpec as well,
  // since they rely on this threshold for determining when to align originalAddrMRange to addrMRange.
  val maxDiffForAddressChange = 20L // This number is arbitrary and may require adjustments in the future.
  val maxDiffForTracks = maxDiffForAddressChange

  /**
   * Generic method to divide project links into continuous sections based on a predicate.
   *
   * Continuous section has same roadPart, matching predicate, track and each project link starts from the
   * same addrM where the previous project link ended.
   *
   * @param projectLinks Sequence of project links to divide into sections.
   * @param linkPredicate Predicate function that identifies which links belong to the target section.
   */
  def toContinuousSections(projectLinks: Seq[ProjectLink], linkPredicate: ProjectLink => Boolean): Seq[Seq[ProjectLink]] = {
    var sections = Seq.empty[Seq[ProjectLink]]
    var currentSection = Seq.empty[ProjectLink]
    for (link <- projectLinks) {
      if (currentSection.isEmpty) {
        // Start a new section if the link matches the predicate
        if (linkPredicate(link)) {
          currentSection :+= link
        }
      } else {
        // Check if the current link continues the section
        val lastLink = currentSection.last
        if (link.roadPart == lastLink.roadPart &&
          linkPredicate(link) &&
          lastLink.addrMRange.continuesTo(link.addrMRange) &&
          lastLink.track == link.track) {
          currentSection :+= link
        } else {
          // If it doesn't match, finalize the current section and start a new one
          sections :+= currentSection
          currentSection = Seq.empty
          if (linkPredicate(link)) {
            currentSection :+= link
          }
        }
      }
    }
    // Add the last section if it exists
    if (currentSection.nonEmpty)
      sections :+= currentSection
    sections
  }

  def updateProjectLinksList(modifiedProjectLinks: Seq[ProjectLink], projectLinksToUpdate: Seq[ProjectLink]): Seq[ProjectLink] = {
    val modifiedLinksMap = modifiedProjectLinks.map(link => link.id -> link).toMap // Convert to Map for fast lookups
    projectLinksToUpdate.map(link => modifiedLinksMap.getOrElse(link.id, link))    // Replace if found, otherwise keep original
  }

  def areTracksCloseEnoughOnOriginalStartAddrM(leftLink: ProjectLink, rightLink: ProjectLink): Boolean = {
    Math.abs(leftLink.originalAddrMRange.start - rightLink.originalAddrMRange.start) <= maxDiffForTracks
  }

  def areTracksCloseEnoughOnEndAddrM(leftLink: ProjectLink, rightLink: ProjectLink): Boolean = {
    Math.abs(leftLink.addrMRange.end - rightLink.addrMRange.end) <= maxDiffForTracks
  }

  // Clamps the average start address to ensure it does not create a gap or overlap with adjacent links
  def clampSharedStartAddrM(averageStart: Long, sectionLinks: Seq[ProjectLink], previousLinks: Seq[ProjectLink]): Long = {
    val minAverageStart = previousLinks.map(_.addrMRange.start + 1).reduceOption(_ max _).getOrElse(averageStart)
    val maxAverageStart = sectionLinks.map(_.addrMRange.end - 1).min

    if (minAverageStart > maxAverageStart) averageStart
    else math.max(minAverageStart, math.min(averageStart, maxAverageStart))
  }

  def clampSharedEndAddrM(averageEnd: Long, sectionLinks: Seq[ProjectLink], followingLinks: Seq[ProjectLink]): Long = {
    val minAverageEnd = sectionLinks.map(_.addrMRange.start + 1).max
    val maxAverageEnd = followingLinks.map(_.addrMRange.end - 1).reduceOption(_ min _).getOrElse(averageEnd)

    if (minAverageEnd > maxAverageEnd) averageEnd
    else math.max(minAverageEnd, math.min(averageEnd, maxAverageEnd))
  }

  def replaceStartsWith(projectLink: ProjectLink, replacingStartAddrM: Long): ProjectLink = {
    projectLink.copy(
      addrMRange          = AddrMRange(replacingStartAddrM, projectLink.addrMRange.end),
      originalAddrMRange  = AddrMRange(replacingStartAddrM, projectLink.originalAddrMRange.end)
    )
  }

  def replaceEndsWith(projectLink: ProjectLink, replacingEndAddrM: Long): ProjectLink = {
    projectLink.copy(
      addrMRange          = AddrMRange(projectLink.addrMRange.start, replacingEndAddrM),
      originalAddrMRange  = AddrMRange(projectLink.originalAddrMRange.start, replacingEndAddrM)
    )
  }

  /**
   * Finds the link that precedes the given target link. Excludes links of the opposite track.
   *
   * @param links Sequence of project links to search in
   * @param target The target project link whose preceding link we seek
   * @param trackToExclude The opposite track to filter out
   */
  def findPreviousLink(links: Seq[ProjectLink], target: ProjectLink, trackToExclude: Track): Option[ProjectLink] = {
    links.find(pl => pl.track != trackToExclude && target.originalAddrMRange.continuesFrom(pl.originalAddrMRange))
  }

  /**
   * Finds the link that follows the given target link. Excludes links of the opposite track.
   *
   * @param links Sequence of project links to search in
   * @param target The target project link whose following link we seek
   * @param trackToExclude The opposite track to filter out
   */
  def findNextLink(links: Seq[ProjectLink], target: ProjectLink, trackToExclude: Track): Option[ProjectLink] = {
    links.find(pl => pl.track != trackToExclude && target.originalAddrMRange.continuesTo(pl.originalAddrMRange))
  }

}
